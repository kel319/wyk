package com.wyk.redis.aop;

import com.wyk.redis.util.RedisUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 热点判断与升级/降级：根据访问频率与时间窗口决定是否达到热点阈值，
 * 并调用 RedisUtil 进行 upgrade/downgrade，与 KeyInfo 解耦。
 */
public class HotspotService {

    private static final Logger log = LoggerFactory.getLogger(HotspotService.class);

    private final long intervalSeconds;
    private final long threshold;
    private final RedisUtil redisUtil;

    public HotspotService(long intervalSeconds, long threshold, RedisUtil redisUtil) {
        this.intervalSeconds = intervalSeconds;
        this.threshold = threshold;
        this.redisUtil = redisUtil;
    }

    /**
     * 检查并更新热点状态：达到阈值则升级，超过时间窗口且低于阈值一半则降级。
     * 由定时任务对 Caffeine 中所有 KeyInfo 调用，或在每次访问后调用（按需）。
     */
    public void check(KeyInfo keyInfo) {
        if (keyInfo == null || redisUtil == null) return;
        long sum = keyInfo.getFrequency().sum();
        long elapsed = Duration.between(keyInfo.getStartTime(), LocalDateTime.now()).getSeconds();

        if (elapsed > intervalSeconds) {
            if (keyInfo.getHotspot().get() && sum < threshold / 2) {
                if (keyInfo.getHotspot().compareAndSet(true, false)) {
                    redisUtil.downgrade(keyInfo.getKey());
                    log.debug("长时间未访问热点数据,热点降级 key={}", keyInfo.getKey());
                }
                keyInfo.getFrequency().reset();
            }
            keyInfo.setStartTime(LocalDateTime.now());
            return;
        }
        if (sum > threshold) {
            if (keyInfo.getHotspot().compareAndSet(false, true)) {
                redisUtil.upgrade(keyInfo.getKey());
                keyInfo.setStartTime(LocalDateTime.now());
                keyInfo.getFrequency().reset();
                log.debug("达到缓存访问阈值,热点升级 key={}", keyInfo.getKey());
            }
        }
    }
}
