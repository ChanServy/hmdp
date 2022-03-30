package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
@RunWith(SpringRunner.class)
public class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private RedisIdWorker idWorker;

    private final ExecutorService POOL = Executors.newFixedThreadPool(500);

    /**
     * 测试IdWorker
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

}
