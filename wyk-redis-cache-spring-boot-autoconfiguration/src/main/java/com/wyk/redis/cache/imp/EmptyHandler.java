package com.wyk.redis.cache.imp;

import com.fasterxml.jackson.databind.JavaType;
import com.wyk.redis.cache.CacheMissHandler;

import java.util.Collections;
import java.util.List;

public class EmptyHandler implements CacheMissHandler {
    private static final Object NULL_MARKER = new Object();
    @Override
    public Object handle(Object key, JavaType type) {
        if (List.class.isAssignableFrom(type.getRawClass())) {
            return Collections.emptyList();
        }
        return NULL_MARKER;
    }

    public static boolean isNullMarker(Object o) {
        return o == NULL_MARKER;
    }
}
