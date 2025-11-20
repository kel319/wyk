package com.wyk.redis;


import com.wyk.redis.aop.KeyInfo;
import com.wyk.redis.aop.NewRedisAop;
import com.wyk.redis.cache.CacheLock;
import com.wyk.redis.cache.CacheMissHandler;
import com.wyk.redis.cache.imp.LocalReentrantLock;
import com.wyk.redis.cache.imp.RedisLock;
import com.wyk.redis.util.BloomFilter;
import com.wyk.redis.util.RedisUtil;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@AutoConfiguration
@EnableConfigurationProperties(RedisProperties.class)
@AutoConfigureAfter(RedisAutoConfiguration.class)
@ConditionalOnProperty(prefix = "wyk.redis.cache",name = "test",havingValue = "true")
public class CacheLockAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CacheLockAutoConfiguration.class);
    private final RedisProperties redisProperties;
    private final RedisUtil redisUtil;

    public CacheLockAutoConfiguration(RedisProperties redisProperties, RedisUtil redisUtil) {
        this.redisProperties = redisProperties;
        this.redisUtil = redisUtil;
    }

    @Bean
    @ConditionalOnMissingBean(RedisLock.class)
    public RedisLock redisLock(RedisProperties redisProperties,
                               RedisUtil redisUtil) {
        log.info("=== 创建 redisLock Bean ===");
        return new RedisLock(
                redisProperties.getDistributedLockTimeOut(),
                redisUtil
        );
    }

    @Bean
    @ConditionalOnMissingBean(LocalReentrantLock.class)
    public LocalReentrantLock reentrantLock(RedisProperties redisProperties) {
        log.info("=== 创建 reentrantLock Bean ===");
        return new LocalReentrantLock(redisProperties.getLocalLockTimeOut());
    }

    @Bean
    @ConditionalOnMissingBean(name = "lockMap")
    public Map<String, CacheLock> lockMap(ObjectProvider<List<CacheLock>> provider,
                                          RedisLock redisLock,
                                          LocalReentrantLock reentrantLock) {
        log.info("=== 创建 lockMap Bean ===");
        HashMap<String, CacheLock> cacheLockHashMap = new HashMap<>();
        cacheLockHashMap.put("defaultRedis",redisLock);
        cacheLockHashMap.put("defaultLocalReentrant",reentrantLock);
        List<CacheLock> cacheLocks = provider.getIfAvailable(ArrayList::new);
        cacheLocks.stream()
                .filter(cacheLock -> !(cacheLock instanceof RedisLock || cacheLock instanceof LocalReentrantLock))
                .forEach(cacheLock -> {
                    String simpleName = cacheLock.getClass().getSimpleName();
                    String lockKey = generateLockName(simpleName);
                    cacheLockHashMap.put(lockKey,cacheLock);
                    log.info("自定义锁策略 {} 注入成功",lockKey);
                });
        return cacheLockHashMap;
    }

    @Bean
    @ConditionalOnMissingBean(NewRedisAop.class)
    public NewRedisAop newRedisAop(RedisProperties redisProperties,
                                    Map<String, CacheLock> lockMap,
                                    Map<String, CacheMissHandler> cacheMissHandlerMap,
                                    RedisLock redisLock,
                                    RedisUtil redisUtil,
                                    @Autowired(required = false) BloomFilter bloomFilter) {
        log.info("=== 创建 newRedisAop Bean ===");
        return new NewRedisAop(
                lockMap,
                cacheMissHandlerMap,
                new ConcurrentHashMap<>(),
                redisLock,
                bloomFilter,
                redisProperties.isBloom(),
                redisProperties.isNil(),
                redisProperties.isHotspotEnable(),
                redisUtil,
                redisProperties.getNilValue(),
                redisProperties.getLock()
        );
    }
    @Bean
    @ConditionalOnMissingBean(name = "keyInfoMap")
    @ConditionalOnProperty(prefix = "wyk.redis.cache", name = "hotspotEnable", havingValue = "true")
    public Map<String, KeyInfo> keyInfoMap() {
        return new ConcurrentHashMap<>();
    }
    @PostConstruct
    private void init() {
        KeyInfo.staticInj(redisProperties.getInterval(),redisProperties.getThreshold(),redisUtil);
    }

    private String generateLockName(String simpleName) {
        if (simpleName.length() > 4 && simpleName.endsWith("Lock")) {
            String temp = simpleName.substring(0,simpleName.length()-4);
            return temp.substring(0,1).toLowerCase() + temp.substring(1);
        }
        return simpleName.substring(0,1).toLowerCase() + simpleName.substring(1);
    }

}
