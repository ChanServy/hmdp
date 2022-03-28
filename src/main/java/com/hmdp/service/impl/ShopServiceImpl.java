package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ShopMapper shopMapper;

    public static ExecutorService CACHE_REBUILD_POOL = Executors.newFixedThreadPool(2);

    @Override
    public Result queryById(Long id) {
        //用互斥锁的方式解决查询商铺时可能发生的缓存击穿问题
        /*Shop shop = queryByIdWithBreakDownByMutex(id);
        if (shop == null) {
            return Result.fail("商铺不存在");
        }
        return Result.ok(shop);*/
        //用逻辑过期的方式解决查询商铺时可能发生的缓存击穿问题
        Shop shop = queryByIdWithBreakDownByLogicalExpiration(id);
        if (shop == null) {
            return Result.fail("商铺不存在");
        }
        return Result.ok(shop);
    }

    /**
     * 用逻辑过期的方式解决查询商铺时可能发生的缓存击穿问题（高可用性、一致性一般）
     * 这种方式不设置过期时间ttl，添加一个字段标识过期时间，使用逻辑判断过期的方式
     *
     * @param id 商铺id
     * @return shop
     */
    public Shop queryByIdWithBreakDownByLogicalExpiration(Long id) {
        String redisDataJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        if (StrUtil.isNotBlank(redisDataJson)) {
            RedisData redisData = JSONUtil.toBean(redisDataJson, RedisData.class);
            Object data = redisData.getData();
            LocalDateTime expireTime = redisData.getExpireTime();
            Shop shop = JSONUtil.toBean((JSONObject) data, Shop.class);
            if (LocalDateTime.now().isBefore(expireTime)) {
                //返回的数据没过期
                return shop;
            } else {
                //过期了，需要缓存重建，要先从数据库中查询，加一个互斥锁保护mysql的安全
                if (getLock(id)) {
                    //异步去刷新redis的数据
                    CACHE_REBUILD_POOL.submit(() -> {
                        try {
                            saveShopToRedis(id, 20L);
                            TimeUnit.MILLISECONDS.sleep(200);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        } finally {
                            releaseLock(id);
                        }
                    });
                }
                //没拿到锁。不等，直接返回一个过期的数据，但这个数据不一定就是错的
                return shop;
            }
        }
        if (Objects.equals(redisDataJson, "")) {
            return null;
        }
        //缓存查不到的话
        Shop shop = shopMapper.selectById(id);
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(20L));
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
        return shop;
    }

    /**
     * 用互斥锁的方式解决查询商铺时可能发生的缓存击穿问题（高一致性、可用性一般）
     * 这种方式的key有过期时间设置ttl。
     *
     * @param id 商铺id
     * @return shop
     */
    public Shop queryByIdWithBreakDownByMutex(Long id) {
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if (Objects.equals(shopJson, "")) {
            return null;
        }
        //缓存中拿不到数据，需要查询数据库，涉及到数据要考虑缓存穿透和缓存击穿的问题
        try {
            if (!getLock(id)) {
                TimeUnit.SECONDS.sleep(1);
                //递归调用，重新尝试到缓存中取数据，没有数据则尝试获取锁，拿不到锁就再次进到这个代码块
                queryByIdWithBreakDownByMutex(id);
            } else {
                Shop shop = shopMapper.selectById(id);
                //模拟查询数据库的延时
                TimeUnit.MILLISECONDS.sleep(200);
                if (shop == null) {
                    stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                    return null;
                }
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
                return shop;
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            releaseLock(id);
        }
        return null;
    }

    /**
     * 用设置空值的方式解决查询商铺时可能发生的缓存穿透问题
     * 对于缓存中和数据库中不存在的数据, 也对其在缓存中设置默认值Null，为避免占用资源，一般过期时间会比较短；
     * 相对简单，但容易破解。攻击者通过分析数据格式，不重复的请求数据库不存在数据，那这样设置null值方案就等于失效的。
     *
     * @param id 商铺id
     * @return shop
     */
    public Shop queryByIdWithPassThroughBySetNil(Long id) {
        //从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        //判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //有效解决缓存穿透
        if (Objects.equals(shopJson, "")) {
            return null;
        }
        //缓存中不存在，根据id查询数据库
        Shop shop = shopMapper.selectById(id);
        //数据库中不存在，在redis中给这个键设置一个空值，防止缓存穿透，并返回错误
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //数据库中存在，将数据写入redis，这里设置一个超时时间，是为双写一致性方案可能会出现的纰漏兜底
        //即使极端情况发生导致数据库和缓存的数据不一致，那么到达超时时间之后缓存会清空，数据再被访问时会同步新数据
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //返回
        return shop;
    }

    /**
     * 互斥锁获取
     *
     * @param id 商品id
     * @return boolean
     */
    private boolean getLock(Long id) {
        //使用redis的setNx实现互斥锁
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(RedisConstants.LOCK_SHOP_KEY + id, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        //防止空指针，因此使用工具类转换一下
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 互斥锁释放
     *
     * @param id 商品id
     */
    private void releaseLock(Long id) {
        stringRedisTemplate.delete(RedisConstants.LOCK_SHOP_KEY + id);
    }

    /**
     * 模拟数据预热，将数据库中的热点数据放入缓存
     *
     * @param id   商品id
     * @param time 商品key过期时间
     */
    public void saveShopToRedis(Long id, Long time) {
        Shop shop = shopMapper.selectById(id);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(time));
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 更新操作的时候尽量保证数据库和缓存的数据双写一致性
     * 1.选择删除缓存而不是更新缓存
     * 2.要先更新数据库，后删除缓存
     * 3.保证更新数据库和删除缓存这一系列操作的原子性（事务）
     * 3.1单体项目直接加@Transaction
     * 3.2分布式项目使用TCC等分布式事务方案
     */
    @Override
    @Transactional
    public Result updateShopById(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("商铺id不能为空");
        }
        //更新数据库
        shopMapper.updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
