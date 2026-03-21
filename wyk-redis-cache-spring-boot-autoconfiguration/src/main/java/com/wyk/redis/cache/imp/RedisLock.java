package com.wyk.redis.cache.imp;

import com.wyk.redis.cache.CacheLock;
import com.wyk.redis.cache.Status;
import com.wyk.redis.exception.RedisCacheException;
import com.wyk.redis.util.RedisUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedisLock implements CacheLock {

    private static final Logger log = LoggerFactory.getLogger(RedisLock.class);

    private final Long distributedLockTimeOut;
    private final int lockRetryTimes;
    private final long lockWaitMillis;
    private final RedisUtil redisUtil;

    public RedisLock(Long distributedLockTimeOut, Integer lockRetryTimes, Long lockWaitMillis, RedisUtil redisUtil) {
        this.distributedLockTimeOut = distributedLockTimeOut;
        this.lockRetryTimes = lockRetryTimes != null && lockRetryTimes > 0 ? lockRetryTimes : 20;
        this.lockWaitMillis = lockWaitMillis != null && lockWaitMillis > 0 ? lockWaitMillis : 100L;
        this.redisUtil = redisUtil;
    }

    @Override
    public void tryLock(String key, String value) {
        int attempts = 0;
        while (true) {
            boolean b = redisUtil.setDistributedLock(key, value, distributedLockTimeOut);
            if (b) {
                log.debug("获取分布式锁成功,key: {},value: {}", key, value);
                return;
            }
            attempts++;
            if (attempts >= lockRetryTimes) {
                throw new RedisCacheException("获取分布式锁失败", Status.BAD_REQUEST.getCode());
            }
            try {
                Thread.sleep(lockWaitMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RedisCacheException("获取分布式锁被中断", Status.BAD_REQUEST.getCode());
            }
        }
    }

    @Override
    public void unLock(String key, String value) {
        boolean b = redisUtil.delDistributedLock(key, value);
        if (b) {
            log.debug("释放分布式锁成功,key: {},value: {}", key, value);
        } else {
            log.warn("释放分布式锁失败,可能已经超时,key: {},value: {}", key, value);
        }
    }
}
