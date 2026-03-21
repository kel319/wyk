package com.wyk.redis.aop;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.wyk.redis.cache.*;
import com.wyk.redis.cache.imp.EmptyHandler;
import com.wyk.redis.cache.imp.ExceptionHandler;
import com.wyk.redis.cache.imp.NoOpCascadeLoader;
import com.wyk.redis.cache.imp.RedisLock;
import com.wyk.redis.exception.RedisCacheException;
import com.wyk.redis.util.BloomFilter;
import com.wyk.redis.util.RedisUtil;
import com.wyk.redis.util.SpringContextHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Aspect
public class RedisAop {

    private static final Logger log = LoggerFactory.getLogger(RedisAop.class);
    private static final Object HOTSPOT_NIL = new Object();
    private static final SpelExpressionParser parser = new SpelExpressionParser();
    private static final CacheMissHandler exceptionHandler = new ExceptionHandler();
    private final Map<String, CacheLock> lockMap;
    private final Map<String, CacheMissHandler> cacheMissHandlerMap;
    private final LoadingCache<String, KeyInfo> keyInfoCache;
    private final HotspotService hotspotService;
    private final Cache<String, Object> hotspotValueCache;
    private final RedisLock redisLock;
    private final BloomFilter bloomFilter;
    private final boolean bloom;
    private final boolean nil;
    private final boolean hotspotEnable;
    private final RedisUtil redisUtil;
    private final String nilValue;
    private final String lock;

    /**
     * 根据 joinPoint 解析当前方法对应的缓存前缀。
     * useRuntimePrefix=true 时返回 IRedisListCacheWarmup.getModulePrefix()，否则从注解 value 解析（如 "user:byId" -> "user"）。
     * 若方法上无 @RedisCache 或无法解析则返回 null。
     */
    public String getPrefixFromJoinPoint(ProceedingJoinPoint joinPoint,RedisCache redisCache) {
        if (redisCache == null) {
            return null;
        }
        if (redisCache.useRuntimePrefix() && joinPoint.getTarget() instanceof IRedisListCacheWarmup) {
            String prefix = ((IRedisListCacheWarmup<?>) joinPoint.getTarget()).getModulePrefix();
            return prefix != null && !prefix.isEmpty() ? prefix : null;
        }
        return redisCache.value();
    }

    public RedisAop(Map<String, CacheLock> lockMap,
                       Map<String, CacheMissHandler> cacheMissHandlerMap,
                       LoadingCache<String, KeyInfo> keyInfoCache,
                       HotspotService hotspotService,
                       Cache<String, Object> hotspotValueCache,
                       RedisLock redisLock,
                       BloomFilter bloomFilter,
                       boolean bloom,
                       boolean nil,
                       boolean hotspotEnable,
                       RedisUtil redisUtil,
                       String nilValue,
                       String lock) {
        this.lockMap = lockMap;
        this.nil = nil;
        this.cacheMissHandlerMap = cacheMissHandlerMap;
        this.keyInfoCache = keyInfoCache;
        this.hotspotService = hotspotService;
        this.hotspotValueCache = hotspotValueCache;
        this.redisLock = redisLock;
        this.bloomFilter = bloomFilter;
        this.bloom = bloom;
        this.redisUtil = redisUtil;
        this.nilValue = nilValue;
        this.lock = lock;
        this.hotspotEnable = hotspotEnable;
    }

    @Pointcut("@annotation(com.wyk.redis.aop.RedisCache)")
    public void redisCachePointcut() {}

    @Around("redisCachePointcut() && @annotation(redisCache)")
    public Object redisAop(ProceedingJoinPoint joinPoint, RedisCache redisCache) throws Throwable {
        String prefix = getPrefixFromJoinPoint(joinPoint, redisCache);
        // 实体缓存查询,支持批量查询,参数请传主键或是主键list
        if (redisCache.cacheMode() == CacheModel.ENTITY && redisCache.redisModel() == RedisModel.QUERY) {
            Object arg = getArgByContext(joinPoint, redisCache.defaultVal(), redisCache.key());
            List<String> keys = collectIdsFromArg(arg,joinPoint).stream().map(id -> prefix + ":" + id)
                    .collect(Collectors.toList());
            JavaType javaType = getJavaType(joinPoint);
            List<Object> results = new ArrayList<>(keys.size());
            List<Integer> missedIndices = new ArrayList<>();
            for (int i = 0; i < keys.size(); i++) {
                String key = keys.get(i);
                KeyInfo keyInfo = (keyInfoCache != null) ? keyInfoCache.get(key) : null;
                boolean hotspot = keyInfo != null && keyInfo.getHotspot().get();
                Object value = null;
                boolean hit = false;
                if (!hotspot && bloom && bloomFilter != null && redisCache.redisModel() != RedisModel.INSERT) {
                    if (!bloomFilter.mightContain(key)) {
                        Object handlerResult = getHandler(redisCache.handler()).handle(key, javaType);
                        value = EmptyHandler.isNullMarker(handlerResult) ? null : handlerResult;
                        hit = true;
                    }
                }
                if (!hit && hotspot && hotspotValueCache != null) {
                    Object local = hotspotValueCache.getIfPresent(key);
                    if (local != null) {
                        if (hotspotService != null) {
                            keyInfo.increment();
                            hotspotService.check(keyInfo);
                        }
                        value = local == HOTSPOT_NIL ? null : local;
                        hit = true;
                    }
                }
                if (!hit) {
                    Optional<Object> resultRedis = checkRedis(key, redisCache, javaType);
                    if (resultRedis.isPresent()) {
                        value = EmptyHandler.isNullMarker(resultRedis.get()) ? null : resultRedis.get();
                        hit = true;
                    }
                }
                if (hit) {
                    results.add(value);
                } else {
                    results.add(null);
                    missedIndices.add(i);
                }
            }
            if (missedIndices.isEmpty()) {
                return keys.size() == 1 ? results.get(0) : results;
            }
            // 有未命中，回源一次并回填
            log.debug("ENTITY 缓存部分未命中，回源重建 keys={}", missedIndices.stream().map(keys::get).collect(Collectors.toList()));
            CacheLock lockObj = getLock(lock);
            String lockValue = UUID.randomUUID().toString();
            String lockKey = "entity_batch:" + String.join(",", keys);
            lockObj.tryLock(lockKey, lockValue);
            try {
                Object proceedResult = joinPoint.proceed();
                if (keys.size() == 1) {
                    String key = keys.get(0);
                    if (proceedResult != null) {
                        redisUtil.setEntityWithUpdateTime(key, proceedResult);
                        if (bloom && bloomFilter != null) bloomFilter.put(key);
                    } else if (nil) {
                        redisUtil.set(key);
                    }
                    return proceedResult;
                }
                List<?> listResult = proceedResult instanceof List ? (List<?>) proceedResult : Collections.singletonList(proceedResult);
                for (int i : missedIndices) {
                    if (i >= listResult.size()) continue;
                    Object val = listResult.get(i);
                    results.set(i, val);
                    String key = keys.get(i);
                    if (val != null) {
                        redisUtil.setEntityWithUpdateTime(key, val);
                        if (bloom && bloomFilter != null) bloomFilter.put(key);
                    } else if (nil) {
                        redisUtil.set(key);
                    }
                }
                return results;
            } finally {
                lockObj.unLock(lockKey, lockValue);
            }
        }
        return doRedisAop(joinPoint, prefix, redisCache);
    }

    private Object doRedisAop(ProceedingJoinPoint joinPoint, String prefix, RedisCache redisCache) throws Throwable {
        JavaType javaType = getJavaType(joinPoint);
        Object arg = getArgByContext(joinPoint, redisCache.defaultVal(), redisCache.key());
        String key = getKey(arg != null ? arg.toString() : "null",prefix,redisCache);
        KeyInfo keyInfo = (keyInfoCache != null) ? keyInfoCache.get(key) : new KeyInfo(key);
        boolean hotspot = false;
        if (keyInfo != null) {
            hotspot = keyInfo.getHotspot().get();
        }
        if (hotspot && hotspotValueCache != null && redisCache.redisModel() == RedisModel.QUERY) {
            Object local = hotspotValueCache.getIfPresent(key);
            if (local != null) {
                if (keyInfoCache != null && hotspotService != null) {
                    keyInfo.increment();
                    hotspotService.check(keyInfo);
                }
                return local == HOTSPOT_NIL ? null : local;
            }
        }
        switch (redisCache.redisModel()) {
            case QUERY:
                if (redisCache.cacheMode() == CacheModel.LIST) {
                    return queryListMode(joinPoint, key, javaType, prefix);
                }
                return query(joinPoint, redisCache, key, javaType, prefix);
            case UPDATE:
            case DELETE:
                return updateVersionAndUnlink(joinPoint, arg, redisCache, prefix);

            case INSERT:
                return insertListMode(joinPoint, redisCache, prefix);
            default:
                throw RedisCacheException.badRequest("意外的缓存模式");
        }
    }
    private List<Long> collectIdsFromArg(Object arg, ProceedingJoinPoint joinPoint) {
        if (arg == null) {
            return Collections.emptyList();
        }
        Set<Long> ids = new LinkedHashSet<>();
        if (arg.getClass().isArray()) {
            int len = Array.getLength(arg);
            for (int i = 0; i < len; i++) {
                extractId(Array.get(arg, i), joinPoint, ids);
            }
            return new ArrayList<>(ids);
        }
        if (arg instanceof Collection<?>) {
            for (Object e : (Collection<?>) arg) {
                extractId(e, joinPoint, ids);
            }
            return new ArrayList<>(ids);
        }
        extractId(arg, joinPoint, ids);
        return new ArrayList<>(ids);
    }

    private void extractId(Object obj, ProceedingJoinPoint joinPoint, Collection<Long> ids) {
        if (obj == null) return;
        if (obj instanceof Number) {
            ids.add(((Number) obj).longValue());
            return;
        }
        Long pk = getIdFromEntity(obj, joinPoint);
        if (pk != null) {
            ids.add(pk);
            return;
        }
        log.debug("无法从对象中提取主键: {}", obj.getClass().getSimpleName());
    }
    private void runCascadeDelete(List<Long> ids, RedisCache redisCache) {
        if (ids == null || ids.isEmpty()) return;
        if (redisCache.cascadeLoader() == NoOpCascadeLoader.class) return;
        try {
            CascadeLoader loader = SpringContextHolder.getBean(redisCache.cascadeLoader());
            if (loader instanceof NoOpCascadeLoader) return;
            Map<String, List<Long>> allDeletes = new HashMap<>();
            for (Long id : ids) {
                CascadeResult r = loader.load(id);
                if (r != null) {
                    r.getDeletes();
                    for (Map.Entry<String, List<Long>> e : r.getDeletes().entrySet()) {
                        allDeletes.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(e.getValue());
                    }
                }
            }
            for (Map.Entry<String, List<Long>> e : allDeletes.entrySet()) {
                String prefix = e.getKey();
                for (Long id : e.getValue()) {
                    redisUtil.remove(prefix + ":" + id);
                }
            }
        } catch (Exception e) {
            log.warn("级联删除执行失败, cascadeLoader: {}, error: {}", redisCache.cascadeLoader().getSimpleName(), e.getMessage());
        }
    }

    /**
     * 删除缓存keys，支持通配符模式删除；同时清理热点本地缓存，避免脏数据。
     */
    private void removeCacheKeys(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        invalidateHotspotValueCache(keys);
        List<String> exactKeys = new ArrayList<>();
        int patternCount = 0;
        for (String key : keys) {
            if (key.startsWith("PATTERN:")) {
                // 通配符模式删除
                patternCount++;
                String pattern = key.substring(8); // 去掉"PATTERN:"前缀
                redisUtil.removeByPattern(pattern);
            } else {
                // 精确key删除
                exactKeys.add(key);
            }
        }
        if (!exactKeys.isEmpty()) {
            if (exactKeys.size() == 1) {
                redisUtil.remove(exactKeys.get(0));
            } else {
                redisUtil.remove(exactKeys);
            }
        }
        log.debug("DELETE 缓存删除完成, 通配符删除: {} 个, 精确删除: {} 个", patternCount, exactKeys.size());
    }

    /** 增删改时清理热点本地缓存：精确 key 直接 invalidate，PATTERN:xxx 则按前缀匹配移除。 */
    private void invalidateHotspotValueCache(List<String> keysOrPatterns) {
        if (hotspotValueCache == null || keysOrPatterns == null) return;
        for (String item : keysOrPatterns) {
            if (item.startsWith("PATTERN:")) {
                String pattern = item.substring(8);
                String prefix = pattern.endsWith("*") ? pattern.substring(0, pattern.length() - 1) : pattern;
                hotspotValueCache.asMap().keySet().removeIf(k -> k.startsWith(prefix));
            } else {
                hotspotValueCache.invalidate(item);
            }
        }
    }

    /** 统一失效：对当前 prefix 与 deleteRelatedPrefixes 做 prefix:version: 仅当 cleanPattern 时再 SCAN+unlink。 */
    private void applyVersionAndOptionalCleanup(String prefix, String[] relatedPrefixes, RedisCache redisCache) {
        if (prefix != null && !prefix.isEmpty()) {
            redisUtil.incrVersion(prefix);
            if (redisCache.cleanPattern()) {
                redisUtil.removeByPattern(prefix + ":list*");
                redisUtil.removeByPattern(prefix + ":result*");
            }
        }
        if (relatedPrefixes != null) {
            for (String r : relatedPrefixes) {
                if (r == null || r.isEmpty()) continue;
                redisUtil.incrVersion(r);
                if (redisCache.cleanPattern()) {
                    redisUtil.removeByPattern(r + ":*");
                }
            }
        }
    }

    private Object insertListMode(ProceedingJoinPoint joinPoint, RedisCache redisCache, String prefix) throws Throwable {
        Object result = joinPoint.proceed();
        if (prefix == null) {
            return result;
        }
        Runnable redisOps = () -> {
            Object source = result != null ? result : joinPoint.getArgs()[0];
            if (source instanceof Collection) {
                for (Object e : (Collection<?>) source) {
                    Long id = getIdFromEntity(e, joinPoint);
                    if (id == null) continue;
                    String entityKey = prefix + ":" + id;
                    redisUtil.setEntityWithUpdateTime(entityKey, e);
                    updateFullIdListOnInsert(prefix,id);
                    if (bloom && bloomFilter != null) {
                        bloomFilter.put(entityKey);
                    }
                }
            } else {
                Long id = getIdFromEntity(source, joinPoint);
                if (id != null) {
                    String entityKey = prefix + ":" + id;
                    redisUtil.setEntityWithUpdateTime(entityKey, source);
                    updateFullIdListOnInsert(prefix,id);
                    if (bloom && bloomFilter != null) {
                        bloomFilter.put(entityKey);
                    }
                }
            }
            applyVersionAndOptionalCleanup(
                    prefix,
                    redisCache.deleteRelatedPrefixes(),
                    redisCache
            );
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    redisOps.run();
                }
            });
        } else {
            redisOps.run();
        }
        return result;
    }

    private Object updateVersionAndUnlink(ProceedingJoinPoint joinPoint,Object arg, RedisCache redisCache, String prefix) throws Throwable {
        Object result = joinPoint.proceed();
        RedisModel model = redisCache.redisModel();
        Runnable redisOps = () -> {
            if (prefix == null || prefix.isEmpty()) {
                applyVersionAndOptionalCleanup(prefix, redisCache.deleteRelatedPrefixes(), redisCache);
                return;
            }
            if (model == RedisModel.UPDATE) {
                List<String> keyList = getListKeyByPrefixAndEntity(prefix, arg, joinPoint);
                if (keyList.isEmpty()) keyList = getListKeyByPrefixAndEntity(prefix,result, joinPoint);
                invalidateHotspotValueCache(keyList);
                redisUtil.unlinkBatches(keyList);
            } else if (model == RedisModel.DELETE) {
                List<Long> ids = collectIdsFromArg(arg, joinPoint);
                List<String> keyList = ids.stream().map(id -> prefix + ":" + id).collect(Collectors.toList());
                redisUtil.unlinkBatches(keyList);
                invalidateHotspotValueCache(keyList);
                runCascadeDelete(ids, redisCache);
                updateFullIdListOnDelete(prefix,ids);
            }
            applyVersionAndOptionalCleanup(prefix, redisCache.deleteRelatedPrefixes(), redisCache);
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    redisOps.run();
                }
            });
        } else {
            redisOps.run();
        }
        return result;
    }

    private List<String> getListKeyByPrefixAndEntity(String prefix, Object target, ProceedingJoinPoint joinPoint) {
        return collectIdsFromArg(target, joinPoint).stream()
                .map(id -> prefix + ":" + id)
                .collect(Collectors.toList());
    }

    /** 删除时从 prefix:full 的 Set 中移除被删 id，原子操作，无并发冲突。 */
    private void updateFullIdListOnDelete(String prefix, List<Long> removedIds) {
        if (prefix == null || removedIds == null || removedIds.isEmpty()) return;
        try {
            redisUtil.sDel(prefix + ":full", removedIds);
        } catch (Exception e) {
            log.warn("LIST 维护 prefix:full 删除 id 失败, prefix={}, removedIds={}", prefix, removedIds, e);
        }
    }

    /** 新增时向 prefix:full 的 Set 追加 id，原子操作，无并发冲突。 */
    private void updateFullIdListOnInsert(String prefix, Long newId) {
        if (prefix == null || newId == null) return;
        try {
            redisUtil.sAdd(prefix + ":full", newId);
        } catch (Exception e) {
            log.warn("LIST 维护 prefix:full 新增 id 失败, prefix={}, newId={}", prefix, newId, e);
        }
    }

    private Object getEntityFromResultOrArgs(ProceedingJoinPoint joinPoint, Object result) {
        if (result != null && getIdFromEntity(result,joinPoint) != null) {
            return result;
        }
        for (Object arg : joinPoint.getArgs()) {
            if (arg != null && !(arg instanceof Page) && getIdFromEntity(arg,joinPoint) != null) {
                return arg;
            }
        }
        return null;
    }

    /** LIST 模式：列表缓存 ID，全量数据 prefix:id；查询先取 ID 列表再批量取实体。 */
    private Object queryListMode(ProceedingJoinPoint joinPoint, String key, JavaType javaType, String prefix) throws Throwable {
        long version = redisUtil.getVersion(prefix);
        String listKeyWithVersion = key + ":" + version;
        JavaType idListType = TypeFactory.defaultInstance().constructType(IdListCache.class);
        IdListCache idListCache = redisUtil.get(listKeyWithVersion, idListType);
        if (idListCache != null && idListCache.getIds() != null && !idListCache.getIds().isEmpty()) {
            JavaType recordType = getPageRecordType(joinPoint);
            if (recordType != null) {
                List<Object> entities = new ArrayList<>();
                boolean invalid = false;
                for (Long id : idListCache.getIds()) {
                    String entityKey = prefix + ":" + id;
                    Object entity = redisUtil.getEntity(entityKey, recordType);
                    if (entity == null) {
                        invalid = true;
                        log.debug("LIST 缓存: 实体 key 缺失, entityKey={}, 回源", entityKey);
                        break;
                    }
                    entities.add(entity);
                }
                if (!invalid) {
                    Object pageOrList = buildPageOrListFromRecords(joinPoint, entities, idListCache.getTotal());
                    if (pageOrList != null) {
                        log.debug("LIST 缓存命中 key={} prefix={} version={} size={}", listKeyWithVersion, prefix, version, entities.size());
                        if (hotspotEnable && keyInfoCache != null && hotspotService != null) {
                            KeyInfo keyInfo = keyInfoCache.get(key);
                            if (keyInfo != null) {
                                keyInfo.increment();
                            }
                            hotspotService.check(keyInfo);
                            if (keyInfo != null && hotspotValueCache != null && keyInfo.getHotspot().get()) {
                                hotspotValueCache.put(listKeyWithVersion, EmptyHandler.isNullMarker(pageOrList) ? HOTSPOT_NIL : pageOrList);
                            }
                        }
                        return pageOrList;
                    }
                }
            }
            redisUtil.remove(listKeyWithVersion);
        }
        log.debug("LIST 缓存未命中/失效，回源重建 key={} prefix={} version={}", key, prefix, version);
        CacheLock lockObj = getLock(lock);
        String lockValue = UUID.randomUUID().toString();
        lockObj.tryLock(key, lockValue);
        try {
            Object result = joinPoint.proceed();
            if (result != null) {
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            storeListResultAndEntities(joinPoint, result, listKeyWithVersion, prefix, javaType);
                        }
                    });
                } else {
                    storeListResultAndEntities(joinPoint, result, listKeyWithVersion, prefix, javaType);
                }
            } else {
                redisUtil.set(listKeyWithVersion);
            }
            return result;
        } finally {
            lockObj.unLock(key, lockValue);
        }

    }

    private void storeListResultAndEntities(ProceedingJoinPoint joinPoint, Object result, String listKey, String prefix, JavaType javaType) {
        if (result == null) return;
        JavaType recordType = getPageRecordType(joinPoint);
        if (recordType == null) return;
        List<Long> ids = new ArrayList<>();
        List<Object> records = new ArrayList<>();
        long total;
        if (result instanceof Page) {
            Page<?> page = (Page<?>) result;
            for (Object record : page.getRecords()) {
                if (record != null) {
                    Long id = getIdFromEntity(record,joinPoint);
                    if (id != null) {
                        ids.add(id);
                        records.add(record);
                    }
                }
            }
            total = page.getTotal();
        } else if (result instanceof List) {
            List<?> list = (List<?>) result;
            for (Object record : list) {
                if (record != null) {
                    Long id = getIdFromEntity(record,joinPoint);
                    if (id != null) {
                        ids.add(id);
                        records.add(record);
                    }
                }
            }
            total = ids.size();
        } else {
            return;
        }
        // 先写实体 key，再写列表 key
        for (int i = 0; i < ids.size(); i++) {
            String entityKey = prefix + ":" + ids.get(i);
            redisUtil.setEntityWithUpdateTime(entityKey, records.get(i));
        }
        IdListCache idListCache = new IdListCache(ids, total);
        redisUtil.setRandomExpires(listKey, idListCache);
        log.debug("LIST 缓存写入 listKey={} prefix={} size={}", listKey, prefix, ids.size());
    }

    private Long getIdFromEntity(Object entity,ProceedingJoinPoint joinPoint) {
        if (entity == null) return null;
        if (entity instanceof Boolean) {
            return null;
        }
        Object target = joinPoint.getTarget();
        if (target instanceof IRedisListCacheWarmup) {
            return ((IRedisListCacheWarmup<?>) target).extractIdFromObject(entity);
        }
        return EntityPkUtil.getPkValue(entity);
    }

    private JavaType getPageRecordType(ProceedingJoinPoint joinPoint) {
        try {
            Type type = ((MethodSignature) joinPoint.getSignature()).getMethod().getGenericReturnType();
            if (type instanceof ParameterizedType) {
                ParameterizedType pt = (ParameterizedType) type;
                if (pt.getRawType() == Page.class && pt.getActualTypeArguments().length > 0) {
                    Type recordType = pt.getActualTypeArguments()[0];
                    if (recordType instanceof TypeVariable) {
                        Type resolved = resolveTypeVariable((TypeVariable<?>) recordType, joinPoint.getTarget().getClass());
                        if (resolved != null) recordType = resolved;
                    }
                    return TypeFactory.defaultInstance().constructType(recordType);
                }
                if (pt.getRawType() == List.class && pt.getActualTypeArguments().length > 0) {
                    Type recordType = pt.getActualTypeArguments()[0];
                    if (recordType instanceof TypeVariable) {
                        Type resolved = resolveTypeVariable((TypeVariable<?>) recordType, joinPoint.getTarget().getClass());
                        if (resolved != null) recordType = resolved;
                    }
                    return TypeFactory.defaultInstance().constructType(recordType);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private Object buildPageOrListFromRecords(ProceedingJoinPoint joinPoint, List<Object> entities, long total) {
        try {
            Type type = ((MethodSignature) joinPoint.getSignature()).getMethod().getGenericReturnType();
            if (type instanceof ParameterizedType) {
                ParameterizedType pt = (ParameterizedType) type;
                if (pt.getRawType() == Page.class) {
                    Object pageArg = getFirstArgOfType(joinPoint, Page.class);
                    if (pageArg instanceof Page) {
                        //noinspection unchecked
                        Page<Object> page = (Page<Object>) pageArg;
                        Page<Object> out = new Page<>(page.getCurrent(), page.getSize(), total);
                        out.setRecords(entities);
                        return out;
                    }
                }
                if (pt.getRawType() == List.class) {
                    return entities;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private Object getFirstArgOfType(ProceedingJoinPoint joinPoint, Class<?> type) {
        for (Object arg : joinPoint.getArgs()) {
            if (type.isInstance(arg)) return arg;
        }
        return null;
    }

    private Object query(ProceedingJoinPoint joinPoint, RedisCache redisCache, String key, JavaType javaType, String prefix) throws Throwable {
        String effectiveKey = (prefix != null) ? key + ":" + redisUtil.getVersion(prefix) : key;
        Optional<Object> redisResult = checkRedis(effectiveKey, redisCache, javaType);
        if (redisResult.isPresent()) {
            return EmptyHandler.isNullMarker(redisResult.get()) ? null : redisResult.get();
        }
        log.debug("QUERY 缓存未命中，开始重建缓存, key: {}, method: {}",
                effectiveKey, joinPoint.getSignature().toShortString());
        return getLock(lock).executeWithLock(joinPoint, effectiveKey, redisUtil);
    }

    private EvaluationContext getContextByJoinPoint(ProceedingJoinPoint joinPoint, String defaultVal) {
        try {
            String[] parameterNames = Optional.ofNullable(joinPoint)
                    .map(ProceedingJoinPoint::getSignature)
                    .filter(MethodSignature.class::isInstance)
                    .map(MethodSignature.class::cast)
                    .map(MethodSignature::getParameterNames)
                    .orElseThrow(() -> RedisCacheException.badRequest("获取参数名称时发生异常"));
            Object[] args = joinPoint.getArgs();
            StandardEvaluationContext context = new StandardEvaluationContext();
            Object target = joinPoint.getTarget();
            context.setRootObject(joinPoint);
            context.setVariable("target", target);
            context.setVariable("this", target);
            for (int i = 0; i < args.length && i < parameterNames.length; i++) {
                context.setVariable(parameterNames[i],
                        args[i] != null ? args[i] : String.format("%s:%s", parameterNames[i], defaultVal));
            }
            return context;
        } catch (Exception e) {
            throw e instanceof RedisCacheException ? (RedisCacheException) e : RedisCacheException.internalServerError("构建SpEL参数上下文失败", e);
        }
    }

    private Object getArgByContext(ProceedingJoinPoint joinPoint, String defaultVal, String value) {
        if (!StringUtils.hasText(value)) return defaultVal;
        EvaluationContext context = getContextByJoinPoint(joinPoint, defaultVal);
        Object result = parser.parseExpression(value).getValue(context);
        return result != null ? result : defaultVal;
    }

    private String getKey(String arg, String prefix, RedisCache redisCache) {
        return String.format("%s:%s:%s:%s",
                prefix, // 前缀,通过调用预热接口实现的前缀提取
                redisCache.cacheMode().getDesc(), // redis模式名字,区分list和result
                arg, // SpEL解析出来的值
                DigestUtils.md5DigestAsHex(redisCache.value().getBytes())); // value的md5哈希,做身份校验
    }

    private Optional<Object> checkRedis(String key, RedisCache redisCache, JavaType javaType) {
        if (!RedisModel.QUERY.equals(redisCache.redisModel())) {
            log.debug("非法类型,将跳过检查缓存逻辑");
            return Optional.empty();
        }
        Object raw = redisUtil.get(key);
        if (raw == null) {
            return Optional.empty();
        }
        if (raw instanceof String && nilValue.equals(raw)) {
            if (nil) {
                return Optional.ofNullable(getHandler(redisCache.handler()).handle(key, javaType));
            }
            return Optional.empty();
        }

        Object redisResult = redisUtil.get(key, javaType);
        if (redisResult == null) {
            try {
                redisUtil.remove(key);
            } catch (Exception ignored) { }
            return Optional.empty();
        }

        if (hotspotEnable && keyInfoCache != null && hotspotService != null) {
            KeyInfo keyInfo = keyInfoCache.get(key);
            if (keyInfo != null) {
                keyInfo.increment();
            }
            hotspotService.check(keyInfo);
            if (keyInfo != null) {
                log.debug("KeyInfo当前访问次数为: {},计算访问窗口为: {}", keyInfo.getFrequency().sum(),
                        Duration.between(keyInfo.getStartTime(), LocalDateTime.now()).getSeconds());
            }
            if (keyInfo != null && hotspotValueCache != null && keyInfo.getHotspot().get()) {
                hotspotValueCache.put(key, EmptyHandler.isNullMarker(redisResult) ? HOTSPOT_NIL : redisResult);
            }
        }
        return Optional.of(redisResult);
    }

    private JavaType getJavaType(ProceedingJoinPoint joinPoint) {
        try {
            Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
            Type type = method.getGenericReturnType();
            if (type instanceof TypeVariable) {
                Type resolved = resolveTypeVariable((TypeVariable<?>) type, joinPoint.getTarget().getClass());
                if (resolved != null) {
                    type = resolved;
                }
            }
            if (type instanceof ParameterizedType) {
                ParameterizedType pt = (ParameterizedType) type;
                if (pt.getRawType() == IPage.class && pt.getActualTypeArguments().length == 1) {
                    JavaType recordType = TypeFactory.defaultInstance().constructType(pt.getActualTypeArguments()[0]);
                    return TypeFactory.defaultInstance().constructParametricType(Page.class, recordType);
                }
            }
            return TypeFactory.defaultInstance().constructType(type);
        } catch (RedisCacheException e) {
            throw e;
        } catch (Exception e) {
            throw RedisCacheException.internalServerError("获取返回值类型失败", e);
        }
    }

    private Type resolveTypeVariable(TypeVariable<?> typeVariable, Class<?> targetClass) {
        String name = typeVariable.getName();
        for (Class<?> c = targetClass; c != null && c != Object.class; c = c.getSuperclass()) {
            Type genericSuper = c.getGenericSuperclass();
            if (!(genericSuper instanceof ParameterizedType)) continue;
            ParameterizedType pt = (ParameterizedType) genericSuper;
            Class<?> raw = (Class<?>) pt.getRawType();
            Type[] typeParams = raw.getTypeParameters();
            Type[] actualArgs = pt.getActualTypeArguments();
            for (int i = 0; i < typeParams.length && i < actualArgs.length; i++) {
                if (typeParams[i] instanceof TypeVariable && name.equals(((TypeVariable<?>) typeParams[i]).getName())) {
                    Type resolved = actualArgs[i];
                    if (resolved instanceof TypeVariable) {
                        resolved = resolveTypeVariable((TypeVariable<?>) resolved, c);
                    }
                    return resolved;
                }
            }
        }
        return null;
    }

    private CacheMissHandler getHandler(String key) {
        return cacheMissHandlerMap != null ? cacheMissHandlerMap.getOrDefault(key, exceptionHandler) : exceptionHandler;
    }
    private CacheLock getLock(String key) {
        return lockMap != null ? lockMap.getOrDefault(key, redisLock) : redisLock;
    }
}