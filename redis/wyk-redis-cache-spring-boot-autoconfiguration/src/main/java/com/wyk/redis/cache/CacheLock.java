package com.wyk.redis.cache;

import com.wyk.redis.util.RedisUtil;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

public interface CacheLock {

    void tryLock(String key, String value) throws Throwable;
    void unLock(String key, String value) throws Throwable;
    default Object executeWithLock(ProceedingJoinPoint joinPoint, String key, RedisUtil redisUtil) throws Throwable {
        String value = UUID.randomUUID().toString();
        tryLock(key,value);
        try {
            Object result = joinPoint.proceed();
            if (result != null) {
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            redisUtil.setRandomExpires(key,result);
                        }
                    });
                } else redisUtil.setRandomExpires(key,result);
            }
            else redisUtil.set(key);
            return result;
        } finally {
            unLock(key,value);
        }
    }

}
