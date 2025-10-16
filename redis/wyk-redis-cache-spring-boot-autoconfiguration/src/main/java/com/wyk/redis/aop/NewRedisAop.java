package com.wyk.redis.aop;


import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.wyk.redis.exception.CustomizeException;
import com.wyk.redis.cache.CacheLock;
import com.wyk.redis.cache.CacheMissHandler;
import com.wyk.redis.cache.Status;
import com.wyk.redis.cache.imp.EmptyHandler;
import com.wyk.redis.cache.imp.ExceptionHandler;
import com.wyk.redis.cache.imp.RedisLock;
import com.wyk.redis.util.BloomFilter;
import com.wyk.redis.util.RedisUtil;
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

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.*;


@Aspect
public class NewRedisAop {

    private static final Logger log = LoggerFactory.getLogger(NewRedisAop.class);
    private static final SpelExpressionParser parser = new SpelExpressionParser();
    private static final CacheMissHandler exceptionHandler = new ExceptionHandler();
    private final Map<String, CacheLock> lockMap;
    private final Map<String,CacheMissHandler> cacheMissHandlerMap;
    private final RedisLock redisLock;
    private final BloomFilter bloomFilter;
    private final boolean bloom;
    private final boolean nil;
    private final RedisUtil redisUtil;
    private final String nilValue;
    private final String lock;


    //构造
    public NewRedisAop(Map<String, CacheLock> lockMap,
                       Map<String, CacheMissHandler> cacheMissHandlerMap,
                       RedisLock redisLock,
                       BloomFilter bloomFilter,
                       boolean bloom,
                       boolean nil,
                       RedisUtil redisUtil,
                       String nilValue,
                       String lock) {
        this.lockMap = lockMap;
        this.nil = nil;
        this.cacheMissHandlerMap = cacheMissHandlerMap;
        this.redisLock = redisLock;
        this.bloomFilter = bloomFilter;
        this.bloom = bloom;
        this.redisUtil = redisUtil;
        this.nilValue = nilValue;
        this.lock = lock;
    }

    //注解驱动
    @Pointcut("@annotation(redisCache)")
    public void redisCachePointcut(RedisCache redisCache){}


    //主逻辑
    @Around("redisCachePointcut(redisCache)")
    public Object redisAop(ProceedingJoinPoint joinPoint,RedisCache redisCache) throws Throwable {
        log.debug("缓存模式为: {}",redisCache.redisModel());
        JavaType javaType = getJavaType(joinPoint);
        if (bloom && !"".equals(redisCache.bloomKey()) && !RedisModel.INSERT.equals(redisCache.redisModel())) {
            Object bloomArg = getArgByContext(joinPoint,redisCache.defaultVal(),redisCache.bloomKey());
            Optional<Object> checkBloomFilter = checkBloomFilter(bloomArg, redisCache, javaType);
            if (checkBloomFilter.isPresent()) return switch (redisCache.redisModel()) {
                case QUERY -> EmptyHandler.isNullMarker(checkBloomFilter.get()) ? null : checkBloomFilter.get();
                case UPDATE, DELETE -> throw new CustomizeException("数据不存在",Status.BAD_REQUEST.getCode());
                default -> throw new CustomizeException("意外的操作类型",Status.BAD_REQUEST.getCode());
            };
        }
        Object arg = getArgByContext(joinPoint, redisCache.defaultVal(), redisCache.key());
        String key = getKey(arg.toString(), redisCache.value());
        return switch (redisCache.redisModel()) {
            case QUERY -> query(joinPoint,redisCache,key,javaType);
            case UPDATE,INSERT,DELETE -> update(joinPoint,key);
        };


    }

    //update分支
    private Object update(ProceedingJoinPoint joinPoint, String key) throws Throwable {
        Object result = joinPoint.proceed();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    redisUtil.remove(key);
                }
            });
        } else redisUtil.remove(key);
        return result;
    }

    //查询分支
    private Object query(ProceedingJoinPoint joinPoint,RedisCache redisCache,String key,JavaType javaType) throws Throwable {

        Optional<Object> redisResult = checkRedis(key, redisCache, javaType);
        if (redisResult.isPresent()) return EmptyHandler.isNullMarker(redisResult.get()) ? null : redisResult.get();
        log.debug("锁策略: {}",lock);
        return getLock(lock).executeWithLock(joinPoint, key, redisUtil);

    }

    //获取SpEL参数上下文
    private EvaluationContext getContextByJoinPoint(ProceedingJoinPoint joinPoint,String defaultVal) {
        String[] parameterNames = Optional.ofNullable(joinPoint)
                .map(ProceedingJoinPoint::getSignature)
                .filter(MethodSignature.class::isInstance)
                .map(MethodSignature.class::cast)
                .map(MethodSignature::getParameterNames)
                .orElseThrow(() -> new CustomizeException("获取参数名称时发生异常"));
        Object[] args = joinPoint.getArgs();
        EvaluationContext context = new StandardEvaluationContext();
        for (int i = 0; i < args.length && i < parameterNames.length; i++) {
            context.setVariable(parameterNames[i],
                    args[i] != null ? args[i] : String.format("%s:%s",parameterNames[i],defaultVal));
        }
        return context;
    }

    //解析SpEL表达式
    private Object getArgByContext(ProceedingJoinPoint joinPoint,String defaultVal,String value) {
        if (!StringUtils.hasText(value)) return defaultVal;
        EvaluationContext context = getContextByJoinPoint(joinPoint, defaultVal);
        Object result = parser.parseExpression(value).getValue(context);
        return result != null ? result : defaultVal;
    }


    //生成key
    private String getKey(String arg,String value) {
        return String.format("%s:%s",arg, DigestUtils.md5DigestAsHex(value.getBytes()));
    }

    //查布隆
    private Optional<Object> checkBloomFilter(Object key, RedisCache redisCache, JavaType javaType) {
        if (key instanceof Collection<?> collect) {
            boolean b = collect.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .anyMatch(bloomFilter::mightContain);
            if (!b) {
                log.debug("在集合中布隆过滤器未查询到key,触发降级: {}",collect);
                return Optional.ofNullable(getHandler(redisCache.handler()).handle(collect,javaType));
            }
        } else {
            String value = String.valueOf(key);
            if (!bloomFilter.mightContain(value)) {
               ;log.debug("布隆过滤器未查询到key,触发降级: {}",value);
                return Optional.ofNullable(getHandler(redisCache.handler()).handle(key,javaType));
            }
        }
        return Optional.empty();
    }

    //查redis
    private Optional<Object> checkRedis(String key, RedisCache redisCache, JavaType javaType) {
        if (!RedisModel.QUERY.equals(redisCache.redisModel())) {
            log.debug("非法类型,将跳过检查缓存逻辑");
            return Optional.empty();
        }
        Object redisResult = redisUtil.get(key, javaType);
        if (redisResult != null) {
            if (nil && nilValue.equals(redisResult))
                return Optional.ofNullable(getHandler(redisCache.handler()).handle(key,javaType));
            else return nilValue.equals(redisResult) ? Optional.empty() : Optional.of(redisResult);
        }
        return Optional.empty();
    }

    //获取方法返回值JavaType
    private JavaType getJavaType(ProceedingJoinPoint joinPoint) {
        Type type = Optional.ofNullable(joinPoint)
                .map(ProceedingJoinPoint::getSignature)
                .filter(MethodSignature.class::isInstance)
                .map(MethodSignature.class::cast)
                .map(MethodSignature::getMethod)
                .map(Method::getGenericReturnType)
                .orElseThrow(() -> new CustomizeException("获取方法返回值失败", Status.INTERNAL_SERVER_ERROR.getCode()));
        return TypeFactory.defaultInstance().constructType(type);
    }

    //空值降级策略选择
    private CacheMissHandler getHandler(String key) {
        return cacheMissHandlerMap.getOrDefault(key,exceptionHandler);
    }
    //锁策略选择
    private CacheLock getLock(String key) {
        return lockMap.getOrDefault(key,redisLock);
    }
}
