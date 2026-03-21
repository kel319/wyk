package com.wyk.redis.aop;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * 纯数据对象：缓存 key 的访问统计与热点标记，不持有 Redis 或任何 static 依赖。
 * 高并发下使用 LongAdder 统计访问次数、AtomicBoolean 表示热点状态。
 */
@Setter
@Getter
public class KeyInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Redis 缓存 key */
    private final String key;
    /** 访问次数（高并发统计） */
    private final LongAdder frequency;
    /** 是否为热点 */
    private final AtomicBoolean hotspot;
    /** 当前统计窗口开始时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime startTime;

    public KeyInfo(String key) {
        this.key = key;
        this.frequency = new LongAdder();
        this.hotspot = new AtomicBoolean(false);
        this.startTime = LocalDateTime.now();
    }

    /** 访问计数 +1，由 AOP 在每次命中缓存时调用 */
    public void increment() {
        frequency.increment();
    }
}
