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
    private String key;

    //构造
    public KeyInfo(String key) {
        this.hotspot = new AtomicBoolean(false);
        this.frequency = new AtomicLong(0L);
        this.startTime = LocalDateTime.now();
        this.key = key;
    }

    //静态注入构造
    public static void staticInj(Long interval, Long threshold, RedisUtil redisUtil) {
        KeyInfo.interval = interval;
        KeyInfo.threshold = threshold;
        KeyInfo.redisUtil = redisUtil;
    }

    //构造
    private KeyInfo(AtomicBoolean hotspot, AtomicLong frequency, LocalDateTime startTime, String key) {
        this.hotspot = hotspot == null ? new AtomicBoolean(false) : hotspot;
        this.frequency = frequency == null ? new AtomicLong(0L) : frequency;
        this.startTime = startTime == null ? LocalDateTime.now() : startTime;
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

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    //工厂方法创建
    public static KeyInfo of(AtomicBoolean hotspot, AtomicLong frequency, LocalDateTime startTime, String key) {
        return new KeyInfo(hotspot,frequency,startTime,key);
    }

    //建造模式静态工厂
    public static Build builder() {
        return new Build();
    }

    //建造模式入口
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

    //判断热点升级与降级
    public static void isHotspot(KeyInfo keyInfo) {
        //判断起始时间和当前时间的差
        if (Duration.between(keyInfo.getStartTime(),LocalDateTime.now()).getSeconds() > interval) {
            if (keyInfo.getHotspot().get() && keyInfo.getFrequency().get() < threshold/2) {
                //CAS降级热点
                if (keyInfo.getHotspot().compareAndSet(true,false)) {
                    redisUtil.downgrade(keyInfo.getKey());
                    log.debug("长时间未访问热点数据,热点降级");
                }
            }
            keyInfo.setStartTime(LocalDateTime.now());
            keyInfo.getFrequency().set(0);
            return;
        }
        //判断在时间差合格时,访问频率是否达到预期
        if (keyInfo.getFrequency().get() > threshold) {
            //CAS升级热点
            if (keyInfo.getHotspot().compareAndSet(false,true)) {
                redisUtil.upgrade(keyInfo.getKey());
                keyInfo.setStartTime(LocalDateTime.now());
                keyInfo.getFrequency().set(0);
                log.debug("达到缓存访问阈值,热点升级");
            }
        }
    }

    //自增1,并检测热点
    public static void increment(Map<String, KeyInfo> keyInfoMap, String key) {
        keyInfoMap.compute(key,(k,oldValue) -> {
            KeyInfo newValue = oldValue == null ? new KeyInfo(k) : oldValue;
            newValue.getFrequency().incrementAndGet();
            isHotspot(newValue);
            return newValue;
        });
    }
}
