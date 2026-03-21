package com.wyk.redis;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.wyk.redis.aop.HotspotService;
import com.wyk.redis.aop.KeyInfo;
import com.wyk.redis.aop.RedisAop;
import com.wyk.redis.cache.CacheLock;
import com.wyk.redis.cache.CacheMissHandler;
import com.wyk.redis.cache.imp.LocalReentrantLock;
import com.wyk.redis.cache.imp.RedisLock;
import com.wyk.redis.util.BloomFilter;
import com.wyk.redis.util.RedisUtil;
import com.wyk.redis.util.SpringContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * 与 soho-admin-service {@code RedisCacheConfiguration} 对齐：LIST/ENTITY 模式、热点、Caffeine KeyInfo。
 */
@AutoConfiguration
@EnableConfigurationProperties(RedisProperties.class)
@AutoConfigureAfter(RedisAutoConfiguration.class)
@ConditionalOnExpression("${wyk.redis.cache.enable:false} == true or ${wyk.redis.cache.test:false} == true")
public class CacheLockAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CacheLockAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(SpringContextHolder.class)
    public SpringContextHolder springContextHolder() {
        return new SpringContextHolder();
    }

    @Bean
    @ConditionalOnMissingBean(RedisLock.class)
    public RedisLock redisLock(RedisProperties redisProperties, RedisUtil redisUtil) {
        log.info("=== 创建 RedisLock Bean ===");
        return new RedisLock(
                redisProperties.getDistributedLockTimeOut(),
                redisProperties.getLockRetryTimes(),
                redisProperties.getLockWaitMillis(),
                redisUtil
        );
    }

    @Bean
    @ConditionalOnMissingBean(LocalReentrantLock.class)
    public LocalReentrantLock reentrantLock(RedisProperties redisProperties) {
        log.info("=== 创建 LocalReentrantLock Bean ===");
        return new LocalReentrantLock(redisProperties.getLocalLockTimeOut());
    }

    @Bean
    @ConditionalOnMissingBean(name = "lockMap")
    public Map<String, CacheLock> lockMap(RedisLock redisLock, LocalReentrantLock reentrantLock) {
        Map<String, CacheLock> map = new HashMap<>();
        map.put("defaultRedis", redisLock);
        map.put("defaultLocalReentrant", reentrantLock);
        map.put("reentrantLock", reentrantLock);
        log.info("=== 创建 lockMap Bean ===");
        return map;
    }

    @Bean
    @ConditionalOnProperty(prefix = "wyk.redis.cache", name = "hotspotEnable", havingValue = "true")
    public LoadingCache<String, KeyInfo> keyInfoCache() {
        return Caffeine.newBuilder()
                .maximumSize(20_000)
                .expireAfterAccess(30, TimeUnit.MINUTES)
                .build(KeyInfo::new);
    }

    @Bean
    @ConditionalOnProperty(prefix = "wyk.redis.cache", name = "hotspotEnable", havingValue = "true")
    public HotspotService hotspotService(RedisProperties props, RedisUtil redisUtil) {
        long interval = props.getInterval() != null ? props.getInterval() : 3600L;
        long threshold = props.getThreshold() != null ? props.getThreshold() : 200L;
        return new HotspotService(interval, threshold, redisUtil);
    }

    @Bean
    @ConditionalOnProperty(prefix = "wyk.redis.cache", name = "hotspotEnable", havingValue = "true")
    public Cache<String, Object> hotspotValueCache() {
        return Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterAccess(30, TimeUnit.MINUTES)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(RedisAop.class)
    public RedisAop redisAop(
            Map<String, CacheLock> lockMap,
            Map<String, CacheMissHandler> cacheMissHandlerMap,
            RedisLock redisLock,
            RedisUtil redisUtil,
            RedisProperties props,
            @Autowired(required = false) LoadingCache<String, KeyInfo> keyInfoCache,
            @Autowired(required = false) HotspotService hotspotService,
            @Autowired(required = false) Cache<String, Object> hotspotValueCache,
            @Autowired(required = false) BloomFilter bloomFilter) {
        log.info("=== 创建 RedisAop Bean (@RedisCache 切面) ===");
        return new RedisAop(
                lockMap,
                cacheMissHandlerMap,
                keyInfoCache,
                hotspotService,
                hotspotValueCache,
                redisLock,
                bloomFilter != null ? bloomFilter : createDefaultBloomFilter(),
                props.isBloom(),
                props.isNil(),
                props.isHotspotEnable(),
                redisUtil,
                props.getNilValue(),
                props.getLock()
        );
    }

    private static BloomFilter createDefaultBloomFilter() {
        int expectedSize = 10000;
        double m = (-expectedSize * Math.log(0.01)) / (Math.log(2) * Math.log(2));
        int bitArraySize = (int) Math.ceil(m);
        return new BloomFilter(new AtomicLongArray((bitArraySize + 63) / 64), bitArraySize, 5);
    }
}
