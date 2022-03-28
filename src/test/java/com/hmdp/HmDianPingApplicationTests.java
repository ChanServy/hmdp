package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;

@SpringBootTest
@RunWith(SpringRunner.class)
public class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    /**
     * 模拟数据预热
     */
    @Test
    public void testSaveShop() {
        shopService.saveShopToRedis(1L, 20L);
    }

}
