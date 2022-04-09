package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;

import static com.hmdp.utils.RabbitMQConstants.ASYNC_CREATE_ORDER_KEY;
import static com.hmdp.utils.RabbitMQConstants.ASYNC_ORDER_EXCHANGE;

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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private SeckillVoucherMapper seckillVoucherMapper;

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private RedisIdWorker idWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private RabbitTemplate rabbitTemplate;


    // 加载lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("secondKill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 创建阻塞队列
    // private final BlockingQueue<VoucherOrder> orderTasksQueue = new ArrayBlockingQueue<>(1024 * 1024);
    // 单线程线程池
    // private static final ExecutorService SECKILL_ORDER_POOL = Executors.newSingleThreadExecutor();

    /*
    创建任务
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            for (; ; ) {
                try {
                    // 获取队列中的订单信息
                    // take方法，获取和删除该队列的头部，如果队列中为空，则阻塞等待；也就是说队列中没有元素会卡在这里，有元素才会继续，不用担心死循环浪费CPU
                    VoucherOrder voucherOrder = orderTasksQueue.take();
                    // 创建订单
                    createVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    log.error("处理订单异常", e);
                    return;
                }
            }
        }
    }
    */

    /*
    项目一启动，用户随时都可能会来秒杀，因此我们要让类一初始化完就来执行这个任务
    @PostConstruct//在当前类初始化完毕以后就来执行
    private void init() {
        SECKILL_ORDER_POOL.submit(new VoucherOrderHandler());
    }
    */

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

        // 锁实现下单业务，下面三个方法，第一个是synchronized锁，第二个是基于redis自己实现的分布式锁，第三个是redisson实现分布式锁
        // VoucherOrder voucherOrder = updateStockAndSaveOrder3(voucherId);
        // if (voucherOrder == null) {
        //     return Result.fail("下单失败！");
        // }
        // 返回订单号
        // return Result.ok(voucherOrder.getId());


        // 使用redis调用lua脚本的方式实现下单业务
        Long userId = UserHolder.getUser().getId();
        // 执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        // 判断结果是否为0
        assert result != null;
        if (result.intValue() != 0) {
            // 不为0，根据lua脚本中我们自己写的逻辑，代表没有购买资格
            return Result.fail(result.intValue() == 1 ? "库存不足！" : "不能重复下单！");
        }
        // 为0了，有购买资格，把下订单信息保存到阻塞队列中
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = idWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);

        // 放入阻塞队列，由线程池异步处理减库存和入库任务
        // orderTasksQueue.add(voucherOrder);

        // 放入阻塞队列中，如果数据量过大，那么会对JVM的内存造成很大的压力；另外如果这个服务宕机重启，那么阻塞队列中的数据就丢失了
        // 因此基于阻塞队列这种异步处理的思想，我们引入RabbitMQ消息队列
        log.info("send voucherOrder message: {}", voucherOrder);
        // 生产者发送消息到exchange后没有绑定的queue时将消息退回
        rabbitTemplate.setReturnCallback((message, replyCode, replyText, exchange, routingKey) ->
                log.info("发送优惠券订单消息被退回！exchange: {}, routingKey: {}", exchange, routingKey));
        // 生产者发送消息confirm检测
        this.rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.info("消息发送失败！cause：{}，correlationData：{}", cause, correlationData);
            } else {
                log.info("消息发送成功！");
            }
        });
        rabbitTemplate.convertAndSend(ASYNC_ORDER_EXCHANGE, ASYNC_CREATE_ORDER_KEY, voucherOrder);

        // 返回
        return Result.ok(orderId);
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

    /**
     * 使用阻塞队列异步创建订单时使用的方法
     *
     * @param voucherOrder voucherOrder
     */
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long voucherId = voucherOrder.getVoucherId();
        // 这块不用加锁，因为在lua脚本判断过“一人一单”，并且判断过“库存充足”，库存充足且符合一人一单，程序才会跑到这里
        // 扣减库存
        UpdateWrapper<SeckillVoucher> updateWrapper = new UpdateWrapper<>();
        updateWrapper.setSql("stock = stock - 1");
        updateWrapper.eq("voucher_id", voucherId);
        updateWrapper.gt("stock", 0);
        seckillVoucherMapper.update(null, updateWrapper);
        // 订单入数据库
        voucherOrderMapper.insert(voucherOrder);
    }
}
