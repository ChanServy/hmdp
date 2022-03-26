package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Objects;
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
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ShopMapper shopMapper;

    @Override
    public Result queryById(Long id) {
        //从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        //判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //有效解决缓存穿透
        if (Objects.equals(shopJson, "")) {
            return Result.fail("店铺不存在");
        }
        //缓存中不存在，根据id查询数据库
        Shop shop = shopMapper.selectById(id);
        //数据库中不存在，在redis中给这个键设置一个空值，防止缓存穿透，并返回错误
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail("店铺不存在");
        }
        //数据库中存在，将数据写入redis，这里设置一个超时时间，是为双写一致性方案可能会出现的纰漏兜底
        //即使极端情况发生导致数据库和缓存的数据不一致，那么到达超时时间之后缓存会清空，数据再被访问时会同步新数据
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //返回
        return Result.ok(shop);
    }

    /**
     * 更新操作的时候尽量保证数据库和缓存的数据双写一致性
     * 1.选择删除缓存而不是更新缓存
     * 2.要先更新数据库，后删除缓存
     * 3.保证更新数据库和删除缓存这一系列操作的原子性（事务）
     *  3.1单体项目直接加@Transaction
     *  3.2分布式项目使用TCC等分布式事务方案
     */
    @Override
    @Transactional
    public Result updateShopById(Shop shop) {
        Long id = shop.getId();
        if (id == null){
            return Result.fail("商铺id不能为空");
        }
        //更新数据库
        shopMapper.updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
