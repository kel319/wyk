package com.wyk.redis.aop;

import com.wyk.redis.cache.CascadeLoader;
import com.wyk.redis.cache.imp.NoOpCascadeLoader;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RedisCache {
    String value() default "defaultValue";
    String key();
    String defaultVal() default "defaultVal";
    RedisModel redisModel() default RedisModel.QUERY;
    CacheModel cacheMode() default CacheModel.RESULT;
    boolean useRuntimePrefix() default true;
    String handler() default "exception";
    Class<? extends CascadeLoader> cascadeLoader() default NoOpCascadeLoader.class;
    String[] deleteRelatedPrefixes() default {};
    boolean cleanPattern() default false;
}
