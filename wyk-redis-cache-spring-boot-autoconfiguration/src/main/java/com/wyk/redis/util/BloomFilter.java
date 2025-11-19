package com.wyk.redis.util;


import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLongArray;

public class BloomFilter {


    private final AtomicLongArray atomic;
    private final int size;
    private final int hashCount;

    public BloomFilter(AtomicLongArray atomic, int size, int hashCount) {
        this.atomic = atomic;
        this.size = size;
        this.hashCount = hashCount;
    }

    //增加
    public void put(String key) {
        int[] hashes = getHashes(key);
        for (int hash : hashes) {
            int longIndex = hash/64;
            int bitIndex = hash%64;
            long mark = 1L << bitIndex;
            atomic.getAndUpdate(longIndex,old -> old | mark);
        }
    }
    public void put(Long key) {
        byte[] array = ByteBuffer.allocate(Long.BYTES).putLong(key).array();
        int[] hashes = getHashes(array);
        for (int hash : hashes) {
            int longIndex = hash/64;
            int bitIndex = hash%64;
            long mark = 1L << bitIndex;
            atomic.getAndUpdate(longIndex,old -> old | mark);
        }
    }
    //验证
    public boolean mightContain(String key) {
        int[] hashes = getHashes(key);
        for (int hash : hashes) {
            int longIndex = hash/64;
            int bitIndex = hash%64;
            long mark = 1L << bitIndex;
            if ((atomic.get(longIndex) & mark) == 0) {
                return false;
            }
        }
        return true;
    }

    public boolean mightContain(Long key) {
        byte[] array = ByteBuffer.allocate(Long.BYTES).putLong(key).array();
        int[] hashes = getHashes(array);
        for (int hash : hashes) {
            int longIndex = hash/64;
            int bitIndex = hash%64;
            long mark = 1L << bitIndex;
            if ((atomic.get(longIndex) & mark) == 0) {
                return false;
            }
        }
        return true;
    }


    //计算key哈希数组
    private int[] getHashes(String key) {
        int[] results = new int[hashCount];
        byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
        int hash1 = hash(bytes,0);
        int hash2 = hash(bytes,hash1);
        for (int i = 0; i < hashCount; i++) {
            results[i] = Math.abs(hash1 + i * hash2) % size;
        }
        return results;
    }

    private int[] getHashes(byte[] bytes) {
        int[] results = new int[hashCount];
        int hash1 = hash(bytes,0);
        int hash2 = hash(bytes,hash1);
        for (int i = 0; i < hashCount; i++) {
            results[i] = Math.abs(hash1 + i * hash2) % size;
        }
        return results;
    }
    //简单哈希算法
    private int hash(byte[] bytes, int seed) {
        int h = seed;
        for (byte b : bytes) {
            h = 31 * h + b;
        }
        return h;
    }
}