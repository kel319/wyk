package com.wyk.redis.cache;

import com.baomidou.mybatisplus.annotation.TableId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从实体上取唯一主键值（Long）。
 * 基于 MyBatis-Plus 的 @TableId 注解精准定位主键字段，
 * 不再依赖方法名猜测。
 */
public final class EntityPkUtil {

    private static final Logger log = LoggerFactory.getLogger(EntityPkUtil.class);

    // 缓存实体类的主键字段信息，避免重复反射
    private static final Map<Class<?>, Field> PK_FIELD_CACHE = new ConcurrentHashMap<>();

    // 缓存实体类的主键 getter 方法
    private static final Map<Class<?>, Method> PK_GETTER_CACHE = new ConcurrentHashMap<>();

    private EntityPkUtil() {}

    /**
     * 从实体上取主键值，基于 @TableId 注解精准定位。
     * 优先使用 @TableId 注解的字段，如果没有注解，尝试名为 "id" 的字段兜底。
     *
     * @param entity 实体，可为 null
     * @return 主键的 long 值，无法取得时返回 null
     */
    public static Long getPkValue(Object entity) {
        if (entity == null) return null;

        Class<?> clazz = entity.getClass();

        // 1. 优先尝试通过 getter 方法获取（性能最好）
        Long value = getByGetter(clazz, entity);
        if (value != null) return value;

        // 2. 降级：直接通过字段获取
        return getByField(clazz, entity);
    }

    /**
     * 通过 getter 方法获取主键值（优先方式）
     */
    private static Long getByGetter(Class<?> clazz, Object entity) {
        Method getter = PK_GETTER_CACHE.computeIfAbsent(clazz, key -> {
            Field pkField = findPkField(clazz);
            if (pkField == null) return null;

            String fieldName = pkField.getName();
            String getterName = "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);

            try {
                return clazz.getMethod(getterName);
            } catch (NoSuchMethodException e) {
                if (pkField.getType() == boolean.class || pkField.getType() == Boolean.class) {
                    String isName = "is" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
                    try {
                        return clazz.getMethod(isName);
                    } catch (NoSuchMethodException ignored) {}
                }
                log.debug("未找到主键字段 {} 的 getter 方法", fieldName);
                return null;
            }
        });

        if (getter != null) {
            try {
                Object value = getter.invoke(entity);
                return convertToLong(value);
            } catch (Exception e) {
                log.debug("通过 getter 获取主键值失败", e);
            }
        }
        return null;
    }

    /**
     * 通过字段直接获取主键值（降级方式）
     */
    private static Long getByField(Class<?> clazz, Object entity) {
        Field field = PK_FIELD_CACHE.computeIfAbsent(clazz, EntityPkUtil::findPkField);

        if (field == null) {
            log.debug("未找到实体类 {} 的主键字段", clazz.getName());
            return null;
        }

        try {
            field.setAccessible(true);
            Object value = field.get(entity);
            return convertToLong(value);
        } catch (Exception e) {
            log.debug("通过字段获取主键值失败", e);
            return null;
        }
    }

    private static Field findPkField(Class<?> clazz) {
        for (Field field : clazz.getDeclaredFields()) {
            if (field.isAnnotationPresent(TableId.class)) {
                return field;
            }
        }
        Class<?> superClass = clazz.getSuperclass();
        if (superClass != null && superClass != Object.class) {
            Field superField = findPkField(superClass);
            if (superField != null) return superField;
        }
        try {
            Field idField = clazz.getDeclaredField("id");
            log.debug("使用兜底方案，通过 'id' 字段获取主键: {}", clazz.getName());
            return idField;
        } catch (NoSuchFieldException ignored) {}
        return null;
    }

    /**
     * 将值转换为 Long 类型
     */
    private static Long convertToLong(Object value) {
        if (value == null) return null;

        if (value instanceof Long) {
            return (Long) value;
        } else if (value instanceof Integer) {
            return ((Integer) value).longValue();
        } else if (value instanceof Short) {
            return ((Short) value).longValue();
        } else if (value instanceof Byte) {
            return ((Byte) value).longValue();
        } else if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                log.debug("字符串转Long失败: {}", value);
            }
        }

        return null;
    }

    /**
     * 获取主键字段名
     */
    public static String getPkFieldName(Class<?> clazz) {
        Field field = findPkField(clazz);
        return field != null ? field.getName() : "id";
    }

    /**
     * 获取 @TableId 注解的 type 属性
     */
    public static TableId getTableIdAnnotation(Class<?> clazz) {
        Field field = findPkField(clazz);
        return field != null ? field.getAnnotation(TableId.class) : null;
    }

    /**
     * 判断指定字段是否是主键
     */
    public static boolean isPrimaryKey(Class<?> clazz, String fieldName) {
        Field pkField = findPkField(clazz);
        return pkField != null && pkField.getName().equals(fieldName);
    }
}