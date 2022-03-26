package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

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
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ShopTypeMapper shopTypeMapper;

    @Override
    public Result queryTypes() {
        /*
            方式1:存入redis的值为string类型的方式
            //先从缓存中查询
            String shopTypesFromCache = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);
            List<ShopType> shopTypes = JSONUtil.toList(shopTypesFromCache, ShopType.class);
            if (!CollectionUtils.isEmpty(shopTypes)) {
                //缓存中有数据，直接返回
                return Result.ok(shopTypes);
            } else {
                //缓存中没有数据,查询数据库
                QueryWrapper<ShopType> wrapper = new QueryWrapper<>();
                wrapper.orderByAsc("sort");
                List<ShopType> shopTypesFromDatabase = shopTypeMapper.selectList(wrapper);
                String shopTypesJson = JSONUtil.toJsonStr(shopTypesFromDatabase);
                //将查询到的数据放入缓存
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_KEY, shopTypesJson);
                return Result.ok(shopTypesFromDatabase);
            }
        */
        //方式2:存入redis的值为list类型的方式
        //先从缓存中查询
        List<String> range = stringRedisTemplate.opsForList().range(CACHE_SHOP_TYPE_KEY, 0, -1);
        List<ShopType> result = new ArrayList<>();
        if (!CollectionUtils.isEmpty(range)) {
            //缓存中如果有数据
            for (String shopTypeJson : range) {
                ShopType shopType = JSONUtil.toBean(shopTypeJson, ShopType.class);
                result.add(shopType);
            }
            return Result.ok(result);
        }
        //缓存中如果没有数据,从数据库中查询
        QueryWrapper<ShopType> wrapper = new QueryWrapper<>();
        wrapper.orderByAsc("sort");
        result = shopTypeMapper.selectList(wrapper);
        List<String> shopTypeJsons = new ArrayList<>();
        for (ShopType shopType : result) {
            String shopTypeJson = JSONUtil.toJsonStr(shopType);
            shopTypeJsons.add(shopTypeJson);
        }
        //将查询到的数据放入缓存
        stringRedisTemplate.opsForList().rightPushAll(CACHE_SHOP_TYPE_KEY, shopTypeJsons);
        return Result.ok(result);
    }
}
