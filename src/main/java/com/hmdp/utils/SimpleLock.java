package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.List;
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

    public SimpleLock(String businessName, StringRedisTemplate redisTemplate) {
        this.businessName = businessName;
        this.redisTemplate = redisTemplate;
    }

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        // 静态的，在类的初始化阶段执行，初始化只在类加载的时候执行一次
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
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
     * 释放分布式锁，使用lua脚本的方式
     */
    @Override
    public void releaseLock() {
        List<String> key = Collections.singletonList(KEY_PREFIX + businessName);
        String threadID = ID_PREFIX + Thread.currentThread().getId();
        //调用Lua脚本，判断线程标识和释放锁放在一个脚本中，保证原子性
        redisTemplate.execute(UNLOCK_SCRIPT, key, threadID);
    }

    /*
    之前的实现方式，可以看到判断线程标识和释放锁是两句代码，不能保证原子性
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
    }*/
}
