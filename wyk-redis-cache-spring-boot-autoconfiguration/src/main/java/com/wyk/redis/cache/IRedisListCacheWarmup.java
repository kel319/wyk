package com.wyk.redis.cache;

import java.util.List;
import java.util.function.Function;

/**
 * LIST 模式启动预热：各模块实现此接口，在应用启动时提供全量数据，
 * 按 module:id 写入每条实体，并可选写入全量 ID 列表（prefix:list:full）。
 */
public interface IRedisListCacheWarmup<T> {
    String getModulePrefix();
    List<T> loadFullData();
    Function<T,Long> getExtractPK();
    default Long extractPk(T entity) {
        return getExtractPK().apply(entity);
    }
    @SuppressWarnings("unchecked")
    default Long extractIdFromObject(Object entity) {
        if (entity == null) {
            return null;
        }
        try {
            return extractPk((T) entity);
        } catch (Exception e) {
            return EntityPkUtil.getPkValue(entity);
        }
    }
}
