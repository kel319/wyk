package com.wyk.redis.cache;

import com.fasterxml.jackson.databind.JavaType;

public interface CacheMissHandler {
    Object handle(String key, JavaType type);
}

