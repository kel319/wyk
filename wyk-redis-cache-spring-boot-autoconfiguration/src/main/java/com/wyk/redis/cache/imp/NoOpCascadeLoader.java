package com.wyk.redis.cache.imp;

import com.wyk.redis.cache.CascadeLoader;
import com.wyk.redis.cache.CascadeResult;

/**
 * 不级联删除时的默认实现，返回空结果。
 */
public class NoOpCascadeLoader implements CascadeLoader {

    @Override
    public CascadeResult load(Long id) {
        return CascadeResult.empty();
    }
}
