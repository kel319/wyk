package com.wyk.redis.cache;

import lombok.Data;

/**
 * 仅用于 Redis 的 value 结构：data 存实体的 JSON 字符串，updateTime 为更新时间。
 * 直接存 JSON 进 Redis，避免 @class 导致的跨服务反序列化问题。
 */
@Data
public class RedisCacheEntry {

    /** 实体的 JSON 字符串，无 type id */
    private String data;
    /** 更新时间戳（毫秒），来自 DB 的 last_update_time 等，用于缓存失效判断 */
    private Long updateTime;

    public RedisCacheEntry() {}

    public RedisCacheEntry(String data, Long updateTime) {
        this.data = data;
        this.updateTime = updateTime;
    }
}
