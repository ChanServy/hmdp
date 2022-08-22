package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * @author CHAN
 * @since 2022-03-30
 */
@Component
public class RedisIdWorker {

    /*LocalDateTime time = LocalDateTime.of(2022, 1, 1, 0, 0, 0);
    long second = time.toEpochSecond(ZoneOffset.UTC);
    second = 1640995200*/
    /**
     * 开始时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    private static final int COUNT_BITS = 32;

    private final StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String keyPrefix) {
        //1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        //当前时间戳
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        //生成时间戳
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        //2.生成序列号
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //将redis中 "increment:" + keyPrefix + ":" + date键 对应的值自增并返回自增之后的值，起初没有这个键的话，incr后会新建并返回1
        //key最后拼接date，可以统计当天一天的订单量
        long count = stringRedisTemplate.opsForValue().increment("incr:" + keyPrefix + ":" + date);//redis的incr为原子操作

        //3.拼接并返回
        return timestamp << COUNT_BITS | count;
    }

    public static void main(String[] args) {
        LocalDateTime time = LocalDateTime.of(2022, 1, 1, 0, 0, 0);
        long second = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println("second = " + second);
    }
}
