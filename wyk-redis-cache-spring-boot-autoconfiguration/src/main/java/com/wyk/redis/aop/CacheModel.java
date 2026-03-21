package com.wyk.redis.aop;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 缓存模式：结果模式缓存完整返回值；列表模式缓存 ID 列表 + 按模块:id 存单条，查询时先取 ID 列表再批量取实体。
 */
@Getter
@RequiredArgsConstructor
public enum CacheModel {
    /** 缓存完整结果，通配符删除 */
    RESULT("result"),
    /** 列表缓存 ID 列表，实体按 module:id 存储；新加数据时易失效，启动时可全量预热 */
    LIST("list"),
    /** 实体缓存,主键查询用 */
    ENTITY("entity");

    private final String desc;


}
