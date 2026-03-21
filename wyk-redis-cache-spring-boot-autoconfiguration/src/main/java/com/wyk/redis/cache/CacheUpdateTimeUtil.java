package com.wyk.redis.cache;

import java.lang.reflect.Method;
import java.util.Date;

/**
 * 从实体上取「更新时间」转成时间戳，仅用于 Redis 包装写入。
 * 不要求实体实现接口，通过反射 getLastUpdateTime/getUpdateTime 获取，不修改实体类。
 */
public final class CacheUpdateTimeUtil {

    private CacheUpdateTimeUtil() {}

    /**
     * 从实体取更新时间（毫秒时间戳）。优先 getLastUpdateTime()，其次 getUpdateTime()。
     * 若实体无该字段或为 null，返回 null。
     */
    public static Long getUpdateTimeMillis(Object entity) {
        if (entity == null) return null;
        try {
            Date date = null;
            for (String methodName : new String[]{"getLastUpdateTime", "getUpdateTime"}) {
                try {
                    Method m = entity.getClass().getMethod(methodName);
                    Object v = m.invoke(entity);
                    if (v instanceof Date) {
                        date = (Date) v;
                        break;
                    }
                } catch (NoSuchMethodException ignored) {
                    // continue
                }
            }
            return date != null ? date.getTime() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
