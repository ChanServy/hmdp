package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private SeckillVoucherMapper seckillVoucherMapper;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private RedisIdWorker idWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    /**
     * 秒杀优惠券
     *
     * @param voucherId 优惠券id
     * @return Result
     */
    @Override
    // @Transactional
    public Result seckill(Long voucherId) {
        //根据id查询秒杀优惠券信息
        SeckillVoucher seckillVoucher = seckillVoucherMapper.selectById(voucherId);
        //判断秒杀活动是否已经开始
        LocalDateTime beginTime = seckillVoucher.getBeginTime();
        if (LocalDateTime.now().isBefore(beginTime)) {
            return Result.fail("秒杀活动未开始！");
        }
        //判断秒杀活动是否已经结束
        LocalDateTime endTime = seckillVoucher.getEndTime();
        if (LocalDateTime.now().isAfter(endTime)) {
            return Result.fail("秒杀活动已经结束！");
        }
        //判断是否还有库存
        Integer stock = seckillVoucher.getStock();
        if (stock < 1) {
            return Result.fail("库存不足！");
        }

        VoucherOrder voucherOrder = updateStockAndSaveOrder3(voucherId);
        if (voucherOrder == null) {
            return Result.fail("下单失败！");
        }
        return Result.ok(voucherOrder);
    }

    /**
     * 单服务或者单体项目，一人一单业务可以使用synchronized锁
     * 如果是将服务集群部署，synchronized锁就失效了，synchronized只能保证单个JVM内部的多个线程之间互斥
     *
     * @param voucherId 优惠券id
     * @return 订单对象VoucherOrder
     */
    @Transactional//事务，保证原子性，减库存和生成订单两个操作同时成功、同时失败，单机项目这么用，分布式项目微服务间要用分布式事务
    public VoucherOrder updateStockAndSaveOrder(Long voucherId) {
        //每人同样的优惠券只能购买一次，一人一单，是对来自同一个用户的并发抢购请求进行判断，判断其线程安全问题
        //同一个用户的请求来了，我们才去判断并发安全问题，如果并发请求不是来自同一个用户，就不需要加锁，因此锁对象选择用户id
        //我们期望的是用户id值一样的请求用同一把锁。
        //假如同一个用户发了很多次抢购请求，每一个请求来到这个地方都能得到一个id对象，不同线程中的这个id值相同但对象不同，对象变锁就变，这不行
        //我们要求的是值一样，因此使用toString，这次值一样了，但是看源码得知toString最后返回了一个new String()，因此也是个新对象，还不行
        //因此再进一步调用字符串的intern方法，这个方法是去常量池里面找到跟这个值一样的那个字符串的地址或者说引用，然后返回给我们
        //这样一来，只要值确定，不管new了多少个String，只要值是一样的最终返回的结果永远是一样的。这样就能确保当用户id的值一样时，锁对象就一样。
        Long userId = UserHolder.getUser().getId();//ThreadLocal中获取到的，每一个请求就得一个线程，ThreadLocal是线程私有的。
        synchronized (userId.toString().intern()) {
            QueryWrapper<VoucherOrder> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("user_id", userId);
            queryWrapper.eq("voucher_id", voucherId);
            Integer count = voucherOrderMapper.selectCount(queryWrapper);
            //user_id用户之前已经对voucher_id优惠券抢购过了，不能重复下单
            if (count > 0) {
                return null;
            }
            /*
                Mybatis的方式实现
                // 注：这块不能用  seckillVoucher.getStock()-1，因为是高并发，不能通过上面查到的库存值传递计算，要直接基于数据库层面使用sql运算！！！
                // mysql连接驱动的更新操作默认返回的并不是受影响的行数，如果想设置返回值是受影响的行数，修改数据库链接配置：增加useAffectedRows=true
                int modified = seckillVoucherMapper.updateStock(voucherId);
                if (modified != 1) {
                    return Result.fail("库存不足！");
                }
            */

            /*
                Mybatis-plus实现，基于Service层的接口
                // 注：这块不能用  seckillVoucher.getStock()-1  ！！！
                boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                        .eq("voucher_id", voucherId).gt("stock", 0).update();
                if (!success) {
                    return Result.fail("库存不足！");
                }
            */

            // Mybatis-plus实现，基于Mapper层的接口
            UpdateWrapper<SeckillVoucher> updateWrapper = new UpdateWrapper<>();
            // 注：这块不能用  seckillVoucher.getStock()-1，因为是高并发，不能通过上面查到的库存值传递计算，要直接基于数据库层面使用sql运算！
            // 因此只能使用.setSql("sql语句")，不能用.set()方法，也不能是new对象设置属性值的方式。
            updateWrapper.setSql("stock = stock - 1").eq("voucher_id", voucherId).gt("stock", 0);
            // mysql连接驱动的更新操作默认返回的并不是受影响的行数，如果想设置返回值是受影响的行数，修改数据库链接配置：增加useAffectedRows=true
            int modified = seckillVoucherMapper.update(null, updateWrapper);
            //库存不足
            if (modified != 1) {
                return null;
            }
            //创建订单
            VoucherOrder voucherOrder = new VoucherOrder();//要在synchronized里面new
            voucherOrder.setId(idWorker.nextId("order"));
            voucherOrder.setUserId(userId);
            voucherOrder.setVoucherId(voucherId);
            //订单入库
            voucherOrderMapper.insert(voucherOrder);
            //返回订单
            return voucherOrder;
        }
    }

    /**
     * 使用Redis实现分布式锁来解决高并发场景下一人一单的线程安全问题
     * 通过redis调用lua脚本来保证 比较线程标识 和 释放锁 两个动作的原子性，防误删
     *
     * @param voucherId 优惠券id
     * @return 优惠券订单
     */
    @Transactional
    public VoucherOrder updateStockAndSaveOrder2(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //以userId为键，锁user，降低锁粒度，提升效率
        SimpleLock simpleLock = new SimpleLock("order:" + userId, stringRedisTemplate);
        boolean lock = simpleLock.getLock(10);
        if (!lock) {
            return null;
        } else {
            try {
                //这里加锁的意义在于只能有一个线程去查询数据库中的订单，确保一人一单
                QueryWrapper<VoucherOrder> queryWrapper = new QueryWrapper<>();
                queryWrapper.eq("user_id", userId);
                queryWrapper.eq("voucher_id", voucherId);
                Integer count = voucherOrderMapper.selectCount(queryWrapper);
                if (count != 0) {
                    //证明之前购买过
                    return null;
                }
                //扣减库存
                UpdateWrapper<SeckillVoucher> updateWrapper = new UpdateWrapper<>();
                updateWrapper.setSql("stock = stock - 1");
                updateWrapper.eq("voucher_id", voucherId);
                updateWrapper.gt("stock", 0);
                int modified = seckillVoucherMapper.update(null, updateWrapper);
                if (modified != 1) {
                    return null;
                }
                VoucherOrder voucherOrder = new VoucherOrder();
                voucherOrder.setVoucherId(voucherId);
                voucherOrder.setUserId(userId);
                voucherOrder.setId(idWorker.nextId("order"));
                voucherOrderMapper.insert(voucherOrder);
                return voucherOrder;
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                simpleLock.releaseLock();
            }
        }
        return null;
    }

    /**
     * 使用Redisson的分布式锁解决高并发场景下一人一单的线程安全问题
     *
     * @param voucherId 优惠券id
     * @return 优惠券订单
     */
    // @SneakyThrows
    @Transactional
    public VoucherOrder updateStockAndSaveOrder3(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 获取锁（可重入），指定锁的名称就是key，这里以userId为键，锁user，降低锁粒度，提升效率
        RLock lock = redissonClient.getLock("order:" + userId);
        // 尝试获取锁，参数分别是：获取锁的最大等待时间（期间会重试），锁自动释放时间，时间单位。
        // 不传参数就是默认-1，不会等待，获取失败立即结束；默认超时时间为30s
        boolean isLock = lock.tryLock();
        if (!isLock) {
            return null;
        } else {
            try {
                //这里加锁的意义在于只能有一个线程去查询数据库中的订单，确保一人一单
                QueryWrapper<VoucherOrder> queryWrapper = new QueryWrapper<>();
                queryWrapper.eq("user_id", userId);
                queryWrapper.eq("voucher_id", voucherId);
                Integer count = voucherOrderMapper.selectCount(queryWrapper);
                if (count != 0) {
                    //证明之前购买过
                    return null;
                }
                //扣减库存
                UpdateWrapper<SeckillVoucher> updateWrapper = new UpdateWrapper<>();
                updateWrapper.setSql("stock = stock - 1");
                updateWrapper.eq("voucher_id", voucherId);
                updateWrapper.gt("stock", 0);
                int modified = seckillVoucherMapper.update(null, updateWrapper);
                if (modified != 1) {
                    return null;
                }
                VoucherOrder voucherOrder = new VoucherOrder();
                voucherOrder.setVoucherId(voucherId);
                voucherOrder.setUserId(userId);
                voucherOrder.setId(idWorker.nextId("order"));
                voucherOrderMapper.insert(voucherOrder);
                return voucherOrder;
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                lock.unlock();
            }
        }
        return null;
    }
}
