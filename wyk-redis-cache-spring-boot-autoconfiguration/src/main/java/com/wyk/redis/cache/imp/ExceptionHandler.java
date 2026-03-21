package com.wyk.redis.cache.imp;

import com.fasterxml.jackson.databind.JavaType;
import com.wyk.redis.cache.CacheMissHandler;
import com.wyk.redis.exception.RedisCacheException;

public class ExceptionHandler implements CacheMissHandler {
    @Override
    public Object handle(Object key, JavaType type) {
        throw RedisCacheException.notFound("查询失败,数据不存在: " + key);
    }
}
