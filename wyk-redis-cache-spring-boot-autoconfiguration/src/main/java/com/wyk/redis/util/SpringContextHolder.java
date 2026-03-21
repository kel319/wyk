package com.wyk.redis.util;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.lang.NonNull;
/**
 * 供 {@link com.wyk.redis.aop.RedisAop} 等通过类型动态获取 Spring Bean（如 {@link com.wyk.redis.cache.CascadeLoader}）。
 * 由 {@link com.wyk.redis.CacheLockAutoConfiguration} 注册为 Bean。
 */
public class SpringContextHolder implements ApplicationContextAware {

    private static ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(@NonNull ApplicationContext context) {
        SpringContextHolder.applicationContext = context;
    }

    public static <T> T getBean(Class<T> clazz) {
        return applicationContext.getBean(clazz);
    }
}
