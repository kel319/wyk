package com.wyk.redis.aop;


import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.wyk.redis.cache.CacheMissHandler;
import com.wyk.redis.cache.Status;
import com.wyk.redis.cache.imp.EmptyHandler;
import com.wyk.redis.cache.imp.ExceptionHandler;
import com.wyk.redis.util.BloomFilter;
import com.wyk.redis.util.RedisUtil;
import com.wyk.redis.util.SpELUtil;
import com.wyk.redis.exception.CustomizeException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.DigestUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

@Aspect

public class RedisAop {
    @Pointcut("@annotation(redisInterface)")
    public void redisCachePointcut(RedisInterface redisInterface){}
    private static final ConcurrentHashMap<String, AtomicReference<ReentrantLock>> reentrantLock = new ConcurrentHashMap<>();
    private static final Logger log = LoggerFactory.getLogger(RedisAop.class);
    private static final SpELUtil spELUtil = new SpELUtil();
    private final RedisUtil redisUtil;
    private final Map<String, CacheMissHandler> missHandler;
    private static final ExceptionHandler defaultMissHandler = new ExceptionHandler();
    private final BloomFilter bloomFilter;

    private final boolean cluster;
    private final boolean bloom;
    private final boolean nil;
    private final String nilValue;
    private final Long localLockTimeOut;
    private final Long distributedLockTimeOut;


    public RedisAop(
            RedisUtil redisUtil,
            Map<String, CacheMissHandler> missHandler,
            BloomFilter bloomFilter,
            boolean cluster,
            boolean bloom,
            boolean nil,
            String nilValue,
            Long localLockTimeOut,
            Long distributedLockTimeOut
            ) {
        this.redisUtil = redisUtil;
        this.missHandler = missHandler;
        this.bloomFilter = bloomFilter;
        this.cluster = cluster;
        this.bloom = bloom;
        this.nil = nil;
        this.nilValue = nilValue;
        this.localLockTimeOut = localLockTimeOut;
        this.distributedLockTimeOut = distributedLockTimeOut;
    }

    @Around("redisCachePointcut(redisInterface)")
    public Object redisAop(ProceedingJoinPoint joinPoint,RedisInterface redisInterface) throws Throwable {

        String key = generateRedisKey(joinPoint, redisInterface);

        return switch (redisInterface.redisModel()) {
            case QUERY -> queryOrInsert(key,joinPoint,redisInterface);
            case UPDATE,INSERT,DELETE -> updateOrDelete(key,joinPoint,redisInterface);
        };
    }

    //生成key
    private String generateRedisKey(ProceedingJoinPoint joinPoint,RedisInterface redisInterface) {
        String key = spELUtil.parseSpEL(redisInterface.key(), redisInterface.defaultVal(), joinPoint);
        if (key == null) return null;
        return String.format("%s::%s",redisInterface.value(),
                DigestUtils.md5DigestAsHex(key.getBytes(StandardCharsets.UTF_8)));
//                key);
    }
    //查询或插入操作逻辑
    private Object queryOrInsert(String key,ProceedingJoinPoint joinPoint,RedisInterface redisInterface) throws Throwable {
        JavaType javaType = getJavaType(joinPoint);
        if (bloom && redisInterface.bloomKey() != null && !redisInterface.bloomKey().trim().isEmpty()) {
            Object o = spELUtil.parseBloomSpEL(redisInterface.bloomKey(), redisInterface.defaultVal(), joinPoint);
            Optional<Object> optionalBloom = bloomFilterHandler(o, redisInterface.handler(), javaType);
            if (optionalBloom.isPresent()) {
                Object object = optionalBloom.get();
                return EmptyHandler.isNullMarker(object)?null:object;
            }
        }
        Optional<Object> optionalRedis = redisGetHandler(redisInterface.handler(), key, javaType);
        if (optionalRedis.isPresent()) {
            Object object = optionalRedis.get();
            return EmptyHandler.isNullMarker(object)?null:object;
        }

        //分布式锁方案
        if (cluster) {
            String value = UUID.randomUUID().toString();
            if (redisUtil.setDistributedLock(key, value, distributedLockTimeOut)) {
                try {
                    log.debug("获取分布式锁成功,key: {},value: {}",key,value);
                    //这里是业务逻辑
                    Object object = redisUtil.get(key, javaType);
                    if (Optional.ofNullable(object).filter(o -> !(o instanceof String && nilValue.equals(o))).isPresent())
                        return object;
                    Object proceed = joinPoint.proceed();
                    if (TransactionSynchronizationManager.isSynchronizationActive()) {
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                redisSetHandler(proceed, key);
                            }
                        });
                    } else {
                        redisSetHandler(proceed, key);
                    }
                    return proceed;

                } finally {
                    if (redisUtil.delDistributedLock(key,value)) {
                        log.debug("分布式锁释放成功,key: {},value: {}",key,value);
                    }
                }
            } else {
                log.warn("获取分布式锁失败");
                throw new CustomizeException("服务器繁忙,请稍后重试", Status.BAD_REQUEST.getCode());
            }
        } else {
            AtomicReference<ReentrantLock> lockRef = reentrantLock
                    .computeIfAbsent(key, k -> new AtomicReference<>(new ReentrantLock()));
            ReentrantLock lock = lockRef.get();
            if (lock.tryLock(localLockTimeOut, TimeUnit.SECONDS)) {
                try {
                    Object object = redisUtil.get(key, javaType);
                    if (Optional.ofNullable(object).filter(o -> !nilValue.equals(o)).isPresent())
                        return object;
                    Object proceed = joinPoint.proceed();
                    if (TransactionSynchronizationManager.isSynchronizationActive()) {
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                redisSetHandler(proceed, key);
                            }
                        });
                    } else {
                        redisSetHandler(proceed, key);
                    }
                    return proceed;
                } finally {
                    lock.unlock();
                    safeCleanLocalLock(key,lockRef,lock);
                }
            } else {
                log.warn("获取本地锁 {} 失败", key);
                throw new CustomizeException("服务器繁忙,请稍后重试", Status.BAD_REQUEST.getCode());
            }
        }
    }
    //更新或删除逻辑
    private Object updateOrDelete(String key ,ProceedingJoinPoint joinPoint, RedisInterface redisInterface) throws Throwable {
        Object proceed = joinPoint.proceed();
        redisUtil.remove(key);
        if (proceed != null) {
            redisUtil.setRandomExpires(key,proceed);
        }
        return proceed;
    }
    //获取方法返回值javaType
    private JavaType getJavaType(ProceedingJoinPoint joinPoint) {
        Type returnType = Optional.of(joinPoint)
                .map(ProceedingJoinPoint::getSignature)
                .filter(MethodSignature.class::isInstance)
                .map(MethodSignature.class::cast)
                .map(MethodSignature::getMethod)
                .map(Method::getGenericReturnType)
                .orElseThrow(() -> CustomizeException.internalServerError("获取方法返回值失败"));
        return TypeFactory.defaultInstance().constructType(returnType);
    }

    //查布隆
    private Optional<Object> bloomFilterHandler(Object result,String handler,JavaType javaType) {
        if (result instanceof List<?> s) {
            boolean b = s.stream().anyMatch(list -> list instanceof Long l && bloomFilter.mightContain(l));
            if(!b) {
                log.debug("布隆过滤器未找到List: {},直接结束!", s);
                return Optional.ofNullable(resolveHandler(handler).handle(result, javaType));
            }
        } else if (result instanceof Long l) {
            if (!bloomFilter.mightContain(l)) {
                log.debug("布隆过滤器未找到Long: {},直接结束!", l);
                return Optional.ofNullable(resolveHandler(handler).handle(result, javaType));
            }
        }
        return Optional.empty();
    }



    //查缓存
    private Optional<Object> redisGetHandler(String handler,String key,JavaType javaType) {
        Object redisResult = redisUtil.get(key, javaType);
        if (nil) {
            if (Optional.ofNullable(redisResult)
                    .filter(o -> !(o instanceof String && nilValue.equals(o)))
                    .isPresent()) return Optional.of(redisResult);
            else if (nilValue.equals(redisResult)) {
                log.info("空缓存,直接结束!");
                return Optional.ofNullable(resolveHandler(handler).handle(key, javaType));
            }
        } else {
            if (redisResult != null && !nilValue.equals(redisResult)) return Optional.of(redisResult);
        }
        return Optional.empty();
    }

    private void redisSetHandler(Object proceed,String key) {
        if (nil && proceed == null) redisUtil.set(key);
        else redisUtil.setRandomExpires(key, proceed);
    }
    //策略选择
    private CacheMissHandler resolveHandler(String handlerName) {
        return missHandler.getOrDefault(handlerName,defaultMissHandler);
    }

    //安全清理本地锁
    private void safeCleanLocalLock(String key, AtomicReference<ReentrantLock> lockRef, ReentrantLock lock) {
        if (!lock.hasQueuedThreads()) {
            if (lockRef.compareAndSet(lock,null)) {
                if (reentrantLock.remove(key,lockRef)) {
                    log.debug("本地锁清理成功: {}",key);
                } else {
                    log.debug("本地锁清理失败,可能被其他线程处理: {}",key);
                }
            } else {
                log.debug("锁引用已被其他线程修改: {}",key);
            }
        } else {
            log.debug("有线程在等待锁,暂不释放: {}",key);
        }
    }
}
