package com.wyk.redis.util;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.wyk.redis.cache.CacheUpdateTimeUtil;
import com.wyk.redis.cache.RedisCacheEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Redis 工具类，提供缓存、分布式锁、库存扣减等。
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class RedisUtil {

    private static final ScheduledExecutorService schedule = Executors.newScheduledThreadPool(1);
    private static final long DEFAULT_TIME = 60;
    private static final int BATCH_SIZE = 500;
    private static final int MAX_BATCH_WARN_SIZE = 500;
    private static final long PROGRESS_EXPIRE_HOURS = 24;
    private static final Logger log = LoggerFactory.getLogger(RedisUtil.class);

    private final RedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Long nilTime;
    private final String nilValue;
    private final boolean watchdog;
    private final Long max_expires;
    private final Long min_expires;
    private final Map<String, CompletableFuture<Void>> completableFutureMap = new ConcurrentHashMap<>();

    public RedisUtil(RedisTemplate redisTemplate,
                     ObjectMapper objectMapper,
                     Long nilTime,
                     Long max_expires,
                     Long min_expires,
                     String nilValue,
                     boolean watchdog) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.nilTime = nilTime;
        this.nilValue = nilValue;
        this.watchdog = watchdog;
        this.max_expires = max_expires;
        this.min_expires = min_expires;
    }

    public <T> T get(String key, Class<T> tClass) {
        try {
            Object object = redisTemplate.opsForValue().get(key);
            if (object == null) return null;
            if (object instanceof String) {
                return unwrapAndParse((String) object, tClass);
            }
            // GenericJackson2JsonRedisSerializer 可能反序列化为 Map，需先解包 RedisCacheEntry 格式
            String innerData = extractDataFromCacheEntryFormat(object);
            if (innerData != null) {
                return objectMapper.readValue(innerData, tClass);
            }
            return objectMapper.convertValue(object, tClass);
        } catch (Exception e) {
            log.warn("redis获取失败,key: {},tClass: {},error: {}", key, tClass, e.getMessage());
            return null;
        }
    }

    public <T> T get(String key, JavaType javaType) {
        try {
            Object object = redisTemplate.opsForValue().get(key);
            if (object == null) return null;
            if (object instanceof String) {
                return unwrapAndParse((String) object, javaType);
            }
            // GenericJackson2JsonRedisSerializer 可能反序列化为 Map，需先解包 RedisCacheEntry 格式
            String innerData = extractDataFromCacheEntryFormat(object);
            if (innerData != null) {
                return objectMapper.readValue(innerData, javaType);
            }
            return objectMapper.convertValue(object, javaType);
        } catch (Exception e) {
            log.warn("redis获取失败,key: {},javaType: {},error: {}", key, javaType, e.getMessage());
            return null;
        }
    }

    /** 当 Redis 反序列化为 Map（含 data、updateTime）时，提取内部 data 字符串 */
    private String extractDataFromCacheEntryFormat(Object raw) {
        if (!(raw instanceof Map)) return null;
        Map<?, ?> map = (Map<?, ?>) raw;
        if (!map.containsKey("data") || !map.containsKey("updateTime")) return null;
        Object data = map.get("data");
        return data instanceof String ? (String) data : null;
    }

    /** 兼容 RedisCacheEntry 格式（{ data, updateTime }），解包后解析；否则直接解析 */
    private <T> T unwrapAndParse(String str, Class<T> tClass) throws Exception {
        if (str == null) return null;
        if (str.contains("\"data\"") && str.contains("\"updateTime\"")) {
            RedisCacheEntry entry = objectMapper.readValue(str, RedisCacheEntry.class);
            return entry.getData() != null ? objectMapper.readValue(entry.getData(), tClass) : null;
        }
        return objectMapper.readValue(str, tClass);
    }

    private <T> T unwrapAndParse(String str, JavaType javaType) throws Exception {
        if (str == null) return null;
        if (str.contains("\"data\"") && str.contains("\"updateTime\"")) {
            RedisCacheEntry entry = objectMapper.readValue(str, RedisCacheEntry.class);
            return entry.getData() != null ? objectMapper.readValue(entry.getData(), javaType) : null;
        }
        return objectMapper.readValue(str, javaType);
    }

    public Object get(String key) {
        try {
            Object raw = redisTemplate.opsForValue().get(key);
            if (raw == null) return null;
            if (raw instanceof String) {
                String str = (String) raw;
                if (str.contains("\"data\"") && str.contains("\"updateTime\"")) {
                    RedisCacheEntry entry = objectMapper.readValue(str, RedisCacheEntry.class);
                    return entry.getData();
                }
                return raw;
            }
            return raw;
        } catch (Exception e) {
            log.warn("redis获取失败,key: {},error: {}", key, e.getMessage());
            return null;
        }
    }

    public <T> void set(String key, T value) {
        if (value != null && key != null) {
            try {
                String json = objectMapper.writeValueAsString(value);
                redisTemplate.opsForValue().set(key, json);
            } catch (Exception e) {
                log.warn("设置redis失败,key: {},error: {}", key, e.getMessage());
            }
        }
    }

    /** 获取 prefix 的 version，key 为 prefix:version。 */
    public long getVersion(String prefix) {
        if (prefix == null || prefix.isEmpty()) return 1L;
        try {
            String versionKey = prefix + ":version";
            Object v = redisTemplate.opsForValue().get(versionKey);
            if (v == null) return 1L;
            if (v instanceof Number) return ((Number) v).longValue();
            return 1L;
        } catch (Exception e) {
            log.warn("获取 prefix version 失败, prefix: {}, error: {}", prefix, e.getMessage());
            return 1L;
        }
    }

    /**
     * prefix 的 version+1，key 为 prefix:version（list/result 共用）。
     */
    public void incrVersion(String prefix) {
        if (prefix == null || prefix.isEmpty()) return;
        try {
            String versionKey = prefix + ":version";
            redisTemplate.opsForValue().increment(versionKey);
        } catch (Exception e) {
            log.warn("自增 prefix version 失败, prefix: {}, error: {}", prefix, e.getMessage());
        }
    }

    /**
     * 仅用于 LIST 模式实体缓存。直接存 JSON，避免 @class。
     * value 结构为 { "data": "实体JSON字符串", "updateTime": 时间戳毫秒 }。
     */
    public <T> void setEntityWithUpdateTime(String key, T entity) {
        if (key == null) return;
        if (entity == null) {
            try {
                redisTemplate.opsForValue().set(key, nilValue, nilTime, TimeUnit.SECONDS);
            } catch (Exception e) { log.warn("redis 设置 nil 失败, key: {}", key, e); }
            return;
        }
        try {
            Long updateTime = CacheUpdateTimeUtil.getUpdateTimeMillis(entity);
            String dataJson = objectMapper.writeValueAsString(entity);
            RedisCacheEntry entry = new RedisCacheEntry(dataJson, updateTime);
            String entryJson = objectMapper.writeValueAsString(entry);
            redisTemplate.opsForValue().set(key, entryJson);
        } catch (Exception e) {
            log.warn("设置 redis 实体(含 updateTime) 失败, key: {}, error: {}", key, e.getMessage());
        }
    }

    /**
     * 从 Redis 读出实体。格式：{ "data": "实体JSON", "updateTime": 毫秒 }，无 @class。
     * 兼容 GenericJackson2JsonRedisSerializer 反序列化为 Map 的情况。
     */
    public <T> T getEntity(String key, JavaType recordType) {
        try {
            Object raw = redisTemplate.opsForValue().get(key);
            if (raw == null) return null;
            String dataJson = null;
            if (raw instanceof String) {
                RedisCacheEntry entry = objectMapper.readValue((String) raw, RedisCacheEntry.class);
                dataJson = entry.getData();
            } else {
                dataJson = extractDataFromCacheEntryFormat(raw);
            }
            return dataJson != null ? objectMapper.readValue(dataJson, recordType) : null;
        } catch (Exception e) {
            log.warn("redis 获取实体失败, key: {}, recordType: {}, error: {}", key, recordType, e.getMessage());
            return null;
        }
    }

    /**
     * 从 Redis 读出带 updateTime 的缓存项，用于按更新时间做失效判断。
     * 兼容 GenericJackson2JsonRedisSerializer 反序列化为 Map 的情况。
     */
    public RedisCacheEntry getCacheEntry(String key, JavaType recordType) {
        try {
            Object raw = redisTemplate.opsForValue().get(key);
            if (raw == null) return null;
            if (raw instanceof String) {
                return objectMapper.readValue((String) raw, RedisCacheEntry.class);
            }
            if (raw instanceof Map) {
                return objectMapper.convertValue(raw, RedisCacheEntry.class);
            }
            return null;
        } catch (Exception e) {
            log.warn("redis 获取 CacheEntry 失败, key: {}, recordType: {}, error: {}", key, recordType, e.getMessage());
            return null;
        }
    }

    public <T> void set(String key, T value, long time) {
        if (value != null && key != null) {
            try {
                String json = objectMapper.writeValueAsString(value);
                if (time > 0) {
                    redisTemplate.opsForValue().set(key, json, time, TimeUnit.MINUTES);
                } else {
                    redisTemplate.opsForValue().set(key, json);
                }
            } catch (Exception e) {
                log.warn("设置redis失败,key: {},error: {}", key, e.getMessage());
            }
        }
    }

    public <T> void setDefault(String key, T value) {
        if (value != null && key != null) {
            try {
                redisTemplate.opsForValue().set(key, value, DEFAULT_TIME, TimeUnit.MINUTES);
            } catch (Exception e) {
                log.warn("设置默认过期redis失败,key: {},error: {}", key, e.getMessage());
            }
        }
    }

    public <T> void setRandomExpires(String key, T value) {
        try {
            if (max_expires > min_expires) {
                redisTemplate.opsForValue().set(key, value,
                        ThreadLocalRandom.current().nextLong(min_expires, max_expires), TimeUnit.SECONDS);
            } else {
                redisTemplate.opsForValue().set(key, value,
                        ThreadLocalRandom.current().nextLong(10, 31), TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.warn("设置随机过期redis失败,key: {},error: {}", key, e.getMessage());
        }
    }

    public void set(String key) {
        if (key != null) {
            try {
                redisTemplate.opsForValue().set(key, nilValue, nilTime, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("redis设置NULL值失败,key: {},error: {}", key, e.getMessage());
            }
        }
    }

    public void remove(String key) {
        if (key != null) {
            try {
                redisTemplate.delete(key);
            } catch (Exception e) {
                log.warn("删除指定redis失败,key: {},error: {}", key, e.getMessage());
            }
        }
    }

    /**
     * 批量删除 Redis 中的 key
     */
    public void remove(Collection<String> keys) {
        if (keys != null && !keys.isEmpty()) {
            try {
                redisTemplate.delete(keys);
            } catch (Exception e) {
                log.warn("批量删除redis失败,keys: {},error: {}", keys, e.getMessage());
            }
        }
    }

    public void upgrade(String key) {
        if (key != null) {
            // 热点升级仅标记状态，不再去掉 key 的过期时间，保留原有 TTL
            log.debug("我升级了");
        }
    }

    public void downgrade(String key) {
        if (key != null) {
            try {
                if (max_expires > min_expires) {
                    redisTemplate.expire(key, ThreadLocalRandom.current().nextLong(min_expires, max_expires), TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                log.warn("热点key降级失败,key: {},error: {}", key, e.getMessage());
            }
        }
    }

    public boolean setDistributedLock(String key, String value, Long timeSeconds) {
        try {
            removeOldFuture(key);
            Boolean lock = redisTemplate.opsForValue().setIfAbsent("DistributedLock" + key, value, Duration.ofSeconds(timeSeconds));
            if (Boolean.TRUE.equals(lock) && watchdog) {
                CompletableFuture<Void> future = new CompletableFuture<>();
                ScheduledFuture<?> scheduled = schedule.scheduleAtFixedRate(() -> {
                    Object newKey = redisTemplate.opsForValue().get("DistributedLock" + key);
                    if (value.equals(newKey)) {
                        redisTemplate.expire("DistributedLock" + key, Duration.ofSeconds(timeSeconds));
                        log.debug("锁自动续期");
                    } else {
                        future.complete(null);
                    }
                }, timeSeconds / 3, timeSeconds / 3, TimeUnit.SECONDS);
                future.thenRun(() -> {
                    scheduled.cancel(false);
                    completableFutureMap.remove(key, future);
                    log.debug("自动续期取消");
                });
                completableFutureMap.put(key, future);
            }
            return Boolean.TRUE.equals(lock);
        } catch (Exception e) {
            log.warn("获取分布式锁 {}:{} 失败: {}", key, value, e.getMessage());
        }
        return false;
    }

    public boolean delDistributedLock(String key, String value) {
        String luaScript = "if redis.call('GET',KEYS[1]) == ARGV[1] then\n"
                + "    redis.call('DEL',KEYS[1])\n"
                + "    return 1\n"
                + "end\n"
                + "return 0";
        RedisScript<Long> script = RedisScript.of(luaScript, Long.class);
        try {
            Object exec = redisTemplate.execute(script, List.of("DistributedLock" + key), value);
            Long execute = exec instanceof Long ? (Long) exec : null;
            return execute != null && execute > 0;
        } catch (Exception e) {
            log.warn("释放分布式锁 {}:{} 失败: {}", key, value, e.getMessage());
        }
        return false;
    }

    public boolean deductInv(List<String> keys, Integer num) {
        String luaScript = "local stock = tonumber(redis.call('GET',KEYS[1]) or '0')\n"
                + "if stock == -1 then return -1 end\n"
                + "local num = tonumber(ARGV[1] or '0')\n"
                + "if num == nil or num <= 0 then return 0 end\n"
                + "if stock >= num then\n"
                + "    redis.call('DECRBY',KEYS[1],num)\n"
                + "    redis.call('INCRBY',KEYS[2],num)\n"
                + "    return 1\n"
                + "else return 0 end";
        RedisScript<Integer> longRedisScript = RedisScript.of(luaScript, Integer.class);
        Object exec = redisTemplate.execute(longRedisScript, keys, num.toString());
        Integer result = exec instanceof Integer ? (Integer) exec : null;
        return result != null && (result == 1 || result == -1);
    }

    public boolean addInv(List<String> keys, Integer num) {
        String luaScript = "local cache = tonumber(redis.call('GET',KEYS[2]) or '0')\n"
                + "local num = tonumber(ARGV[1] or '0')\n"
                + "local stock = tonumber(redis.call('GET',KEYS[1]) or '0')\n"
                + "if num == nil or num <= 0 or stock == -1 then return 0 end\n"
                + "if cache >= num then\n"
                + "    redis.call('DECRBY',KEYS[2],num)\n"
                + "    redis.call('INCRBY',KEYS[1],num)\n"
                + "    return 1\n"
                + "end\n"
                + "return 0";
        RedisScript<Integer> integerRedisScript = RedisScript.of(luaScript, Integer.class);
        Object exec = redisTemplate.execute(integerRedisScript, keys, num.toString());
        Integer result = exec instanceof Integer ? (Integer) exec : null;
        return Integer.valueOf(1).equals(result);
    }

    public boolean deductInv(List<String> keys, List<Integer> nums) {
        String luaScript = "for i=1,#KEYS,2 do\n"
                + "    local stock = tonumber(redis.call('GET',KEYS[i])) or 0\n"
                + "    local argIndex = math.floor((i+1)/2)\n"
                + "    local num = tonumber(ARGV[argIndex]) or 0\n"
                + "    if stock < num and stock ~= -1 then return 0 end\n"
                + "end\n"
                + "for i=1,#KEYS,2 do\n"
                + "    local argIndex = math.floor((i+1)/2)\n"
                + "    local num = tonumber(ARGV[argIndex]) or 0\n"
                + "    local stock = tonumber(redis.call('GET',KEYS[i])) or 0\n"
                + "    if stock ~= -1 then\n"
                + "        redis.call('DECRBY',KEYS[i],num)\n"
                + "        redis.call('INCRBY',KEYS[i+1],num)\n"
                + "    end\n"
                + "end\n"
                + "return 1";
        RedisScript<Long> longRedisScript = RedisScript.of(luaScript, Long.class);
        String[] args = nums.stream().map(String::valueOf).toArray(String[]::new);
        Object exec = redisTemplate.execute(longRedisScript, keys, (Object[]) args);
        Long result = exec instanceof Long ? (Long) exec : null;
        return Long.valueOf(1L).equals(result);
    }

    public boolean addInv(List<String> keys, List<Integer> nums) {
        String luaScript = "for i = 1,#KEYS,2 do\n"
                + "    local stock = tonumber(redis.call('GET',KEYS[i])) or 0\n"
                + "    local cache = tonumber(redis.call('GET',KEYS[i+1])) or 0\n"
                + "    local argIndex = math.floor((i+1)/2)\n"
                + "    local num = tonumber(ARGV[argIndex]) or 0\n"
                + "    if cache < num and stock ~= -1 then return 0 end\n"
                + "end\n"
                + "for i=1,#KEYS,2 do\n"
                + "    local stock = tonumber(redis.call('GET',KEYS[i])) or 0\n"
                + "    local argIndex = math.floor((i+1)/2)\n"
                + "    local num = tonumber(ARGV[argIndex]) or 0\n"
                + "    if stock ~= -1 and num ~= 0 then\n"
                + "        redis.call('DECRBY',KEYS[i+1],num)\n"
                + "        redis.call('INCRBY',KEYS[i],num)\n"
                + "    end\n"
                + "end\n"
                + "return 1";
        RedisScript<Long> longRedisScript = RedisScript.of(luaScript, Long.class);
        String[] args = nums.stream().map(String::valueOf).toArray(String[]::new);
        Object exec = redisTemplate.execute(longRedisScript, keys, (Object[]) args);
        Long result = exec instanceof Long ? (Long) exec : null;
        return Long.valueOf(1L).equals(result);
    }

    public boolean paySuccess(List<String> keys, List<Integer> args) {
        String luaScript = "for i = 1,#KEYS,1 do\n"
                + "    local cache = tonumber(redis.call('GET',KEYS[i]) or '0')\n"
                + "    local num = tonumber(ARGV[i] or '0')\n"
                + "    if cache < num then return 0 end\n"
                + "end\n"
                + "for i = 1,#KEYS,1 do\n"
                + "    local cache = tonumber(redis.call('GET',KEYS[i]) or '0')\n"
                + "    local num = tonumber(ARGV[i] or '0')\n"
                + "    if cache >= num and num ~= 0 then\n"
                + "        redis.call('DECRBY',KEYS[i],num)\n"
                + "    end\n"
                + "end\n"
                + "return 1";
        RedisScript<Integer> script = RedisScript.of(luaScript, Integer.class);
        Object exec = redisTemplate.execute(script, keys,
                (Object[]) args.stream().map(String::valueOf).toArray(String[]::new));
        Integer result = exec instanceof Integer ? (Integer) exec : null;
        return Integer.valueOf(1).equals(result);
    }

    private void removeOldFuture(String key) {
        CompletableFuture<Void> future = completableFutureMap.remove(key);
        if (future != null && !future.isDone()) {
            future.complete(null);
            log.debug("手动取消看门狗任务: {}", key);
        }
    }

    /**
     * 使用 SCAN 按模式收集 key（不删除），避免 KEYS 阻塞。用于监控/列表展示。
     */
    public Set<String> scanKeys(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return new TreeSet<>();
        }
        Set<String> result = new TreeSet<>();
        try {
            redisTemplate.execute((RedisCallback<Object>) connection -> {
                ScanOptions scanOptions = ScanOptions.scanOptions()
                        .match(pattern)
                        .count(1000)
                        .build();
                try (Cursor<byte[]> cursor = connection.scan(scanOptions)) {
                    cursor.forEachRemaining(keyBytes -> result.add(new String(keyBytes)));
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("SCAN 获取 key 失败, pattern: {}, error: {}", pattern, e.getMessage());
        }
        return result;
    }

    public void removeByPattern(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return;
        }
        long startTime = System.currentTimeMillis();
        long totalDeleted = 0L;
        try {
            long cursor = 0;
            final byte[] patternBytes = pattern.getBytes(StandardCharsets.UTF_8);
            final byte[] countBytes = String.valueOf(BATCH_SIZE).getBytes(StandardCharsets.UTF_8);
            do {
                final long cur = cursor;
                Object raw = redisTemplate.execute((RedisCallback<Object>) connection -> connection.execute("SCAN", String.valueOf(cur).getBytes(StandardCharsets.UTF_8), "MATCH".getBytes(StandardCharsets.UTF_8), patternBytes, "COUNT".getBytes(StandardCharsets.UTF_8), countBytes));
                long nextCursor = -1;
                List<byte[]> keys = new ArrayList<>();
                if (raw instanceof List) {
                    List<?> list = (List<?>) raw;
                    if (!list.isEmpty()) {
                        Object c = list.get(0);
                        try {
                            if (c instanceof byte[]) nextCursor = Long.parseLong(new String((byte[]) c, StandardCharsets.UTF_8));
                            else if (c instanceof String) nextCursor = Long.parseLong((String) c);
                            else if (c instanceof Number) nextCursor = ((Number) c).longValue();
                        } catch (NumberFormatException e) {
                            log.warn("SCAN 返回 cursor 解析失败, 降级 KEYS");
                            removeByPatternKeys(pattern);
                            return;
                        }
                    }
                    if (list.size() >= 2 && list.get(1) instanceof Collection) {
                        for (Object k : (Collection<?>) list.get(1)) {
                            if (k instanceof byte[]) keys.add((byte[]) k);
                        }
                    }
                } else {
                    log.warn("SCAN 返回格式不符合预期 (非 List), 降级 KEYS");
                    removeByPatternKeys(pattern);
                    return;
                }
                cursor = nextCursor >= 0 ? nextCursor : 0;
                if (!keys.isEmpty()) {
                    Long n = redisTemplate.unlink(keys);
                    totalDeleted += n;
                }
            } while (cursor != 0);
            long cost = System.currentTimeMillis() - startTime;
            log.info("SCAN+边扫边删 完成 pattern: {}, 删除: {} key, 耗时: {}ms", pattern, totalDeleted, cost);
        } catch (Exception e) {
            log.error("SCAN 删除失败 pattern: {}", pattern, e);
            removeByPatternKeys(pattern);
        }
    }

    /**
     * 降级方案：使用 KEYS 命令（不推荐生产环境使用）
     */
    private void removeByPatternKeys(String pattern) {
        try {
            Set<String> keys = redisTemplate.keys(pattern);
            if (!keys.isEmpty()) {
                redisTemplate.unlink(keys);
                log.warn("KEYS 模式删除完成, pattern: {}, deleted keys count: {}", pattern, keys.size());
            }
        } catch (Exception e) {
            log.error("KEYS 模式删除失败, pattern: {}", pattern, e);
        }
    }

    public void unlink(String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        try {
            redisTemplate.unlink(key);
        } catch (Exception e) {
            log.error("Redis unlink失败, key: {}", key, e);
        }
    }
    /**
     * 异步批量删除keys (Redis 4.0+ 推荐)
     * @param keys 要删除的key集合
     * @return 实际删除的key数量
     */
    public Long unlink(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return 0L;
        }
        try {
            return redisTemplate.unlink(keys);
        } catch (Exception e) {
            log.error("Redis批量unlink失败, keys数量: {}", keys.size(), e);
            return 0L;
        }
    }

    /**
     * 使用 pipeline 批量删除 keys，每批一条 UNLINK key1 key2 ...，减少 RTT 与 Redis 命令数。
     *
     * @param keys      要删除的 key 列表
     * @param batchSize 每批 key 数量（每批合并为一条 UNLINK）
     */
    public void unlinkBatches(List<String> keys, int batchSize) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (int i = 0; i < keys.size(); i += batchSize) {
                int endIndex = Math.min(i + batchSize, keys.size());
                List<String> batchKeys = keys.subList(i, endIndex);
                List<byte[]> batchBytes = new ArrayList<>(batchKeys.size());
                for (String k : batchKeys) {
                    batchBytes.add(k.getBytes(StandardCharsets.UTF_8));
                }
                redisTemplate.unlink(batchKeys);
            }
            return null;
        });
        long totalDeleted = 0L;
        for (Object r : results) {
            if (r instanceof Long) {
                totalDeleted += (Long) r;
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("Redis pipeline unlink 完成, keys: {}, 删除: {}", keys.size(), totalDeleted);
        }
    }

    /**
     * 异步批量删除keys (使用默认批次大小 BATCH_SIZE=500)
     *
     * @param keys 要删除的key列表
     */
    public void unlinkBatches(List<String> keys) {
        unlinkBatches(keys, BATCH_SIZE);
    }
    public <T> void sAdd(String key, T value) {
        try {
            redisTemplate.opsForSet().add(key, value);
        } catch (Exception e) {
            log.warn("Redis sAdd 失败, key: {}, value: {}", key, value, e);
        }
    }

    /** 批量添加 Set 成员，原子操作，用于 full 预热等 */
    public void sAddAll(String key, Collection<?> values) {
        if (key == null || values == null || values.isEmpty()) return;
        SAddAllResult r = sAddAllWithResume(key, values instanceof List ? (List<?>) values : new ArrayList<>(values), BATCH_SIZE, false);
        if (!r.isSuccess()) {
            log.warn("Redis sAddAll 失败, key: {}, processed: {}/{}, error: {}", key, r.getProcessedCount(), r.getTotalCount(), r.getErrorMessage());
        }
    }

    /**
     * 批量添加 Set 成员，支持断点续传（游标式、幂等）。
     * 1. 游标式：基于 lastValue 哈希游标；2. 提前校验；3. Pipeline（非 Lua，避免阻塞）；4. 进度 key 含业务标识；5. List 变化可恢复。
     */
    public SAddAllResult sAddAllWithResume(String key, List<?> values, int batchSize, boolean resetProgress) {
        return sAddAllWithResume(key, values, batchSize, resetProgress, null, null);
    }

    public SAddAllResult sAddAllWithResume(String key, List<?> values, int batchSize, boolean resetProgress, String business, String type) {
        if (key == null || values == null || values.isEmpty()) return SAddAllResult.ok(0);
        List<Object> validList = new ArrayList<>();
        for (Object v : values) {
            try {
                if (redisTemplate.getValueSerializer().serialize(v) != null) validList.add(v);
            } catch (Exception e) {
                log.warn("sAddAllWithResume 提前校验跳过无效值, key: {}, value: {}, error: {}", key, v, e.getMessage());
            }
        }
        if (validList.isEmpty()) return SAddAllResult.ok(0);
        String progressKey = (business != null && !business.isEmpty() && type != null && !type.isEmpty())
                ? business + ":" + type + ":" + key + ":progress"
                : "progress:sAdd:" + key;
        String lastValueCursor = null;
        if (resetProgress) {
            try { redisTemplate.delete(progressKey); } catch (Exception e) { log.warn("清除进度失败, progressKey: {}", progressKey, e); }
        } else {
            try {
                Object v = redisTemplate.opsForValue().get(progressKey);
                if (v != null) lastValueCursor = v.toString();
            } catch (Exception e) { log.warn("读取进度失败, progressKey: {}", progressKey, e); }
        }
        Map<String, Integer> cursorToLastIndex = new HashMap<>();
        for (int i = 0; i < validList.size(); i++) {
            cursorToLastIndex.put(valueToCursor(validList.get(i)), i);
        }
        int startIndex = (lastValueCursor != null && !lastValueCursor.isEmpty())
                ? cursorToLastIndex.getOrDefault(lastValueCursor, -1) + 1
                : 0;
        int total = validList.size();
        if (startIndex >= total) {
            try { redisTemplate.delete(progressKey); } catch (Exception ignored) {}
            return SAddAllResult.ok(total);
        }
        int batch = Math.max(1, batchSize);
        if (batchSize > MAX_BATCH_WARN_SIZE) {
            log.warn("sAddAllWithResume batchSize 过大({}), 可能影响 Redis 性能, 建议≤{}", batchSize, MAX_BATCH_WARN_SIZE);
        }
        int maxRetries = 3;
        int processed = startIndex;
        try {
            for (int i = startIndex; i < total; i += batch) {
                int end = Math.min(i + batch, total);
                List<?> batchList = new ArrayList<>(validList.subList(i, end));
                for (int attempt = 0; attempt < maxRetries; attempt++) {
                    try {
                        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                            byte[] rawKey = redisTemplate.getStringSerializer().serialize(key);
                            for (Object v : batchList) {
                                byte[] rawValue = redisTemplate.getValueSerializer().serialize(v);
                                if (rawValue != null) connection.sAdd(rawKey, rawValue);
                            }
                            return null;
                        });
                        break;
                    } catch (Exception e) {
                        if (attempt == maxRetries - 1) throw e;
                        try { Thread.sleep(50L * (attempt + 1)); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(ie);
                        }
                    }
                }
                processed = end;
                String cursor = valueToCursor(validList.get(end - 1));
                redisTemplate.opsForValue().set(progressKey, cursor, PROGRESS_EXPIRE_HOURS, TimeUnit.HOURS);
            }
            redisTemplate.delete(progressKey);
            return SAddAllResult.ok(total);
        } catch (Exception e) {
            log.warn("Redis sAddAllWithResume 失败, key: {}, processed: {}/{}, error: {}", key, processed, total, e.getMessage());
            try {
                String cursor = processed > 0 ? valueToCursor(validList.get(processed - 1)) : "";
                redisTemplate.opsForValue().set(progressKey, cursor, PROGRESS_EXPIRE_HOURS, TimeUnit.HOURS);
            } catch (Exception ex) { log.warn("保存进度失败", ex); }
            return SAddAllResult.partial(processed, total, e.getMessage());
        }
    }

    /** 游标：用哈希，不存明文，避免敏感数据泄露 */
    private String valueToCursor(Object v) {
        if (v == null) return "";
        try {
            byte[] bytes = redisTemplate.getValueSerializer().serialize(v);
            return bytes != null ? DigestUtils.md5DigestAsHex(bytes) : "";
        } catch (Exception e) {
            return DigestUtils.md5DigestAsHex(String.valueOf(v).getBytes(StandardCharsets.UTF_8));
        }
    }
    public <T> Set<T> sGet(String key) {
        try {
            return (Set<T>) redisTemplate.opsForSet().members(key);
        } catch (Exception e) {
            log.warn("Redis sMembers 失败, key: {}", key, e);
            return Collections.emptySet();
        }
    }
    public void sDel(String key, Collection<?> values) {
        try {
            Object[] arr = values.toArray();
            for (int i = 0; i < arr.length; i += BATCH_SIZE) {
                int end = Math.min(i + BATCH_SIZE, arr.length);
                redisTemplate.opsForSet().remove(key,
                        Arrays.copyOfRange(arr, i, end));
            }
        } catch (Exception e) {
            log.warn("Redis sDel 失败, key: {}, values: {}", key, values);
        }
    }
    public boolean sContains(String key, Object value) {
        try {
            return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(key, value));
        } catch (Exception e) {
            log.warn("Redis sContains 失败, key: {}, value: {}", key, value);
            return false;
        }
    }

}
