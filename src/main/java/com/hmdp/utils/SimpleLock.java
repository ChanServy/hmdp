package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 基于redis实现分布式锁
 * @author CHAN
 * @since 2022-03-31
 */
public class SimpleLock implements ILock{

    //业务名称
    private final String businessName;
    private final StringRedisTemplate redisTemplate;
    private static final String KEY_PREFIX = "lock:";
    public SimpleLock(String businessName, StringRedisTemplate redisTemplate) {
        this.businessName = businessName;
        this.redisTemplate = redisTemplate;
    }

    static long id = Thread.currentThread().getId();
    static String uuid = UUID.randomUUID().toString().replaceAll("-", "") + "-";

    /**
     * 添加分布式锁
     * @param timeoutSec 分布式锁未释放，超时自动释放
     * @return boolean
     */
    @Override
    public boolean getLock(long timeoutSec) {
        Boolean success = redisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + businessName, uuid + id, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    /**
     * 释放分布式锁
     */
    @Override
    public void releaseLock() {
        String value = redisTemplate.opsForValue().get(KEY_PREFIX + businessName);
        if (Objects.equals(value, uuid + id)){
            redisTemplate.delete(KEY_PREFIX + businessName);
        }
    }
}
