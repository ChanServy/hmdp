package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootTest
@RunWith(SpringRunner.class)
@Slf4j
public class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private RedisIdWorker idWorker;

    @Resource
    private RedissonClient redissonClient;

    private final ExecutorService POOL = Executors.newFixedThreadPool(500);

    /**
     * 测试IdWorker，生成30000个ID计时
     */
    @Test
    public void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);//设置从300次开始递减
        long begin = System.currentTimeMillis();//开始时间
        for (int i = 0; i < 300; i++) {
            //x300
            POOL.submit(() -> {
                for (int j = 0; j < 100; j++) {
                    //x100
                    long orderId = idWorker.nextId("order");
                    System.out.println("id = " + orderId);
                }
                //每次-1
                latch.countDown();
            });
        }
        latch.await();//等300次执行完
        long end = System.currentTimeMillis();//结束时间
        System.out.println("time = " + (end - begin));//300次总用时
    }

    /**
     * 模拟数据预热
     */
    @Test
    public void testSaveShop() {
        shopService.saveShopToRedis(1L, 20L);
    }

    @Test
    public void testRedisson() throws InterruptedException {
        // 获取锁（可重入），指定锁的名称
        RLock lock = redissonClient.getLock("testLock");
        // 尝试获取锁，参数分别是：获取锁的最大等待时间（期间会重试，不写会默认-1，不等待），锁自动释放时间（不写默认30s），时间单位
        // true代表获取到分布式锁，存在redis中的格式 key:testLock  field:bd32e60a-8e52-455f-a4a9-6262d39c976f:1  value:1
        boolean isLock = lock.tryLock(1, 10, TimeUnit.SECONDS);
        // 判断释放获取成功
        if (isLock) {
            try {
                System.out.println("执行业务逻辑...");
            } finally {
                // 释放锁
                lock.unlock();
            }
        }
    }
}
