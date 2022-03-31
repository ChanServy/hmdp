package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Objects;
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
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    public SimpleLock(String businessName, StringRedisTemplate redisTemplate) {
        this.businessName = businessName;
        this.redisTemplate = redisTemplate;
    }


    /**
     * 添加分布式锁
     * @param timeoutSec 分布式锁未释放，超时自动释放
     * @return boolean
     */
    @Override
    public boolean getLock(long timeoutSec) {
        //获取当前线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取锁
        Boolean success = redisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + businessName, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    /**
     * 释放分布式锁
     */
    @Override
    public void releaseLock() {
        //获取当前线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取redis锁中的标识
        String id = redisTemplate.opsForValue().get(KEY_PREFIX + businessName);
        //判断标识是否一致
        if (Objects.equals(threadId, id)){
            //一致则释放锁
            redisTemplate.delete(KEY_PREFIX + businessName);
        }
    }
}
