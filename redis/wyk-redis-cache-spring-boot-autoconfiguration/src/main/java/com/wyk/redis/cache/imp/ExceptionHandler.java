package com.wyk.redis.cache.imp;

import com.fasterxml.jackson.databind.JavaType;
import com.wyk.redis.exception.CustomizeException;
import com.wyk.redis.cache.CacheMissHandler;
import com.wyk.redis.cache.Status;

public class ExceptionHandler implements CacheMissHandler {
    @Override
    public Object handle(Object key, JavaType type) {
        throw new CustomizeException("查询失败,数据不存在: " + key, Status.BAD_REQUEST.getCode());
    }
}
