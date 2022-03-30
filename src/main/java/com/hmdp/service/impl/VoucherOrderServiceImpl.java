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
import com.hmdp.utils.UserHolder;
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

    /**
     * 秒杀优惠券
     *
     * @param voucherId 优惠券id
     * @return Result
     */
    @Override
    @Transactional//事务，保证原子性，减库存和生成订单两个操作同时成功、同时失败，单机项目这么用，分布式项目微服务间要用分布式事务
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

        VoucherOrder voucherOrder = new VoucherOrder();

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
            if (count > 0) {
                return Result.fail("不能重复购买哦！");
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
            // 注：这块不能用  seckillVoucher.getStock()-1，因为是高并发，不能通过上面查到的库存值传递计算，要直接基于数据库层面使用sql运算！
            // 因此只能使用.setSql("sql语句")，不能用.set()方法，也不能是new对象设置属性值的方式。
            // mysql连接驱动的更新操作默认返回的并不是受影响的行数，如果想设置返回值是受影响的行数，修改数据库链接配置：增加useAffectedRows=true
            UpdateWrapper<SeckillVoucher> updateWrapper = new UpdateWrapper<>();
            updateWrapper.setSql("stock = stock - 1").eq("voucher_id", voucherId).gt("stock", 0);
            int modified = seckillVoucherMapper.update(null, updateWrapper);
            if (modified != 1) {
                return Result.fail("库存不足！");
            }

            //创建订单

            voucherOrder.setId(idWorker.nextId("order"));
            voucherOrder.setUserId(userId);
            voucherOrder.setVoucherId(voucherId);
            //订单入库
            voucherOrderMapper.insert(voucherOrder);
        }
        return Result.ok(voucherOrder);
    }
}
