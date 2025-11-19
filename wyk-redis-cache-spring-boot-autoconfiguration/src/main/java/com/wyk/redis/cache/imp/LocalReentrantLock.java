package com.wyk.redis.cache.imp;


import com.wyk.redis.exception.CustomizeException;
import com.wyk.redis.RedisProperties;
import com.wyk.redis.cache.CacheLock;
import com.wyk.redis.cache.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

@EnableConfigurationProperties(RedisProperties.class)
public class LocalReentrantLock implements CacheLock {

    private static final ConcurrentHashMap<String, AtomicReference<ReentrantLock>> localLock = new ConcurrentHashMap<>();
    private static final Logger log = LoggerFactory.getLogger(LocalReentrantLock.class);

    private final Long localLockTimeOut;

    public LocalReentrantLock(Long localLockTimeOut) {
        this.localLockTimeOut = localLockTimeOut;
    }

    @Override
    public void tryLock(String key, String value) throws InterruptedException {
        AtomicReference<ReentrantLock> lockRef = localLock
                .computeIfAbsent(key, k -> new AtomicReference<>(new ReentrantLock()));
        ReentrantLock lock = lockRef.get();
        if (lock != null && lock.tryLock(localLockTimeOut, TimeUnit.SECONDS)) {
            log.debug("本地锁获取成功,key: {},value: {}",key,value);
        } else {
            log.debug("本地锁获取失败,key: {},value: {}",key,value);
            throw CustomizeException.conflict("服务器繁忙,请稍后重试");
        }
    }

    @Override
    public void unLock(String key, String value) {
        AtomicReference<ReentrantLock> lockRef = localLock.get(key);
        ReentrantLock lock = lockRef.get();
        lock.unlock();
        safeCleanLock(key,lockRef,lock);
    }

    private void safeCleanLock(String key, AtomicReference<ReentrantLock> lockRef, ReentrantLock lock) {

        if (!lock.hasQueuedThreads()) {
            if (lockRef.compareAndSet(lock,null)) {
                if (localLock.remove(key,lockRef)) {
                    log.debug("清理本地锁成功");
                } else log.debug("清理本地锁失败,或许已被其他线程清理");
            } else log.debug("清理本地锁失败,锁引用被其他线程修改");
        } else log.debug("存在等待线程,暂不清理本地锁");

    }


}
