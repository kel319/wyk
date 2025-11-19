package com.wyk.redis.aop;

import com.wyk.redis.util.RedisUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class KeyInfo implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(KeyInfo.class);

    private static Long interval; //热点过期时间间隔
    private static Long threshold; //热点升级条件
    private static RedisUtil redisUtil;



    private AtomicBoolean hotspot;
    private AtomicLong frequency;
    private LocalDateTime startTime;
    private LocalDateTime lastAccessTime;
    private String key;

    public KeyInfo(String key) {
        this.hotspot = new AtomicBoolean(false);
        this.frequency = new AtomicLong(0L);
        this.startTime = LocalDateTime.now();
        this.lastAccessTime = LocalDateTime.now();
        this.key = key;
    }

    public static void staticInj(Long interval, Long threshold, RedisUtil redisUtil) {
        KeyInfo.interval = interval;
        KeyInfo.threshold = threshold;
        KeyInfo.redisUtil = redisUtil;
    }

    private KeyInfo(AtomicBoolean hotspot, AtomicLong frequency, LocalDateTime startTime, String key) {
        this.hotspot = hotspot == null ? new AtomicBoolean(false) : hotspot;
        this.frequency = frequency == null ? new AtomicLong(0L) : frequency;
        this.startTime = startTime == null ? LocalDateTime.now() : startTime;
        this.lastAccessTime = LocalDateTime.now();
        this.key = key;
    }

    public AtomicBoolean getHotspot() {
        return hotspot;
    }

    public void setHotspot(AtomicBoolean hotspot) {
        this.hotspot = hotspot;
    }

    public AtomicLong getFrequency() {
        return frequency;
    }

    public void setFrequency(AtomicLong frequency) {
        this.frequency = frequency;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }


    public LocalDateTime getLastAccessTime() {
        return lastAccessTime;
    }

    public void setLastAccessTime(LocalDateTime lastAccessTime) {
        this.lastAccessTime = lastAccessTime;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public static KeyInfo of(AtomicBoolean hotspot, AtomicLong frequency, LocalDateTime startTime, String key) {
        return new KeyInfo(hotspot,frequency,startTime,key);
    }
    public static Build builder() {
        return new Build();
    }
    public static class Build {
        private AtomicBoolean hotspot;
        private AtomicLong frequency;
        private LocalDateTime startTime;
        private String key;

        public Build() {
            this.startTime = LocalDateTime.now();
        }

        public Build hotspot(AtomicBoolean hotspot) {
            this.hotspot = hotspot;
            return this;
        }
        public Build frequency(AtomicLong frequency) {
            this.frequency = frequency;
            return this;
        }
        public Build startTime(LocalDateTime startTime) {
            this.startTime = startTime;
            return this;
        }
        public Build key(String key) {
            this.key = key;
            return this;
        }

        public KeyInfo build() {
            return new KeyInfo(this.hotspot,this.frequency,this.startTime,this.key);
        }
    }

    public static void isHotspot(KeyInfo keyInfo) {
        if (Duration.between(keyInfo.getLastAccessTime(),LocalDateTime.now()).getSeconds() > interval) {
            if (keyInfo.getHotspot().get()) {
                if (keyInfo.getHotspot().compareAndSet(true,false)) {
                    redisUtil.downgrade(keyInfo.getKey());
                    keyInfo.setLastAccessTime(LocalDateTime.now());
                    log.debug("长时间未访问热点数据,热点降级");
                }
            }
            keyInfo.getFrequency().set(0);
            return;
        }
        if (keyInfo.getFrequency().get() > threshold) {
            if (keyInfo.getHotspot().compareAndSet(false,true)) {
                redisUtil.upgrade(keyInfo.getKey());
                keyInfo.setStartTime(LocalDateTime.now());
                keyInfo.setLastAccessTime(LocalDateTime.now());
                keyInfo.getFrequency().set(0);
                log.debug("达到缓存访问阈值,升级热点");
            }
        }
    }

    public static void increment(Map<String, KeyInfo> keyInfoMap, String key) {
        keyInfoMap.compute(key,(k,oldValue) -> {
            KeyInfo newValue = oldValue == null ? new KeyInfo(k) : oldValue;
            newValue.getFrequency().incrementAndGet();
            isHotspot(newValue);
            return newValue;
        });
    }
}
