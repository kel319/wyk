package com.wyk.redis;


import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "wyk.redis.cache")
public class RedisProperties {
    private boolean enable;
    private boolean test = false;
    private boolean cluster = true;
    private boolean bloom = true;
    private boolean nil = true;
    private boolean watchdog = true; //仅cluster = true有效
    private String nilValue = "__NULL__";
    private String strategy = "Handler";
    private Long nilTime = 30L; //单位s
    private Long localLockTimeOut = 2L;
    private Long distributedLockTimeOut = 30L; //分布式锁超时时间
    private Long maxExpires = 31L;
    private Long minExpires = 10L;
    private Integer expectedSize = 10000;
    private String lock = "defaultRedis";
    private Long interval = 3600L; //热点过期时间间隔
    private Long threshold = 200L; //热点升级条件
    private boolean hotspotEnable = true; //热点检测开启

    public RedisProperties() {
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public String getStrategy() {
        return strategy;
    }

    public Long getMaxExpires() {
        return maxExpires;
    }

    public void setMaxExpires(Long maxExpires) {
        this.maxExpires = maxExpires;
    }

    public Long getMinExpires() {
        return minExpires;
    }

    public void setMinExpires(Long minExpires) {
        this.minExpires = minExpires;
    }

    public void setCluster(boolean cluster) {
        this.cluster = cluster;
    }

    public void setBloom(boolean bloom) {
        this.bloom = bloom;
    }

    public void setNil(boolean nil) {
        this.nil = nil;
    }

    public void setWatchdog(boolean watchdog) {
        this.watchdog = watchdog;
    }

    public void setNilValue(String nilValue) {
        this.nilValue = nilValue;
    }

    public void setNilTime(Long nilTime) {
        this.nilTime = nilTime;
    }

    public void setLocalLockTimeOut(Long localLockTimeOut) {
        this.localLockTimeOut = localLockTimeOut;
    }

    public void setDistributedLockTimeOut(Long distributedLockTimeOut) {
        this.distributedLockTimeOut = distributedLockTimeOut;
    }

    public void setEnable(boolean enable) {
        this.enable = enable;
    }

    public boolean isCluster() {
        return cluster;
    }

    public boolean isBloom() {
        return bloom;
    }

    public boolean isNil() {
        return nil;
    }

    public boolean isWatchdog() {
        return watchdog;
    }

    public String getNilValue() {
        return nilValue;
    }

    public Long getNilTime() {
        return nilTime;
    }

    public Long getLocalLockTimeOut() {
        return localLockTimeOut;
    }

    public Long getDistributedLockTimeOut() {
        return distributedLockTimeOut;
    }

    public boolean isEnable() {
        return enable;
    }

    public Integer getExpectedSize() {
        return expectedSize;
    }

    public void setExpectedSize(Integer expectedSize) {
        this.expectedSize = expectedSize;
    }

    public String getLock() {
        return lock;
    }

    public void setLock(String lock) {
        this.lock = lock;
    }

    public boolean isTest() {
        return test;
    }

    public void setTest(boolean test) {
        this.test = test;
    }

    public Long getInterval() {
        return interval;
    }

    public void setInterval(Long interval) {
        this.interval = interval;
    }

    public Long getThreshold() {
        return threshold;
    }

    public void setThreshold(Long threshold) {
        this.threshold = threshold;
    }

    public boolean isHotspotEnable() {
        return hotspotEnable;
    }

    public void setHotspotEnable(boolean hotspotEnable) {
        this.hotspotEnable = hotspotEnable;
    }
}
