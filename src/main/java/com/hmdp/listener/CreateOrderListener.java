package com.hmdp.listener;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;

import static com.hmdp.utils.RabbitMQConstants.*;

/**
 * @author CHAN
 * @since 2022/4/9
 */
@Component
@Slf4j
public class CreateOrderListener {

    @Resource
    private SeckillVoucherMapper seckillVoucherMapper;

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @RabbitListener(
            bindings = @QueueBinding(
                    value = @Queue(value = ASYNC_ORDER_QUEUE, durable = "true"),
                    exchange = @Exchange(
                            value = ASYNC_ORDER_EXCHANGE,
                            ignoreDeclarationExceptions = "true"
                            // type = ExchangeTypes.DIRECT//默认就是direct类型
                    ),
                    key = ASYNC_CREATE_ORDER_KEY
            )
    )
    public void listen(VoucherOrder voucherOrder, Channel channel, Message message) throws IOException {
        try {
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
            log.info("接收信息成功，开始处理业务！");
            Long voucherId = voucherOrder.getVoucherId();
            log.debug("voucherId: {}, voucherOrder: {}", voucherId, voucherOrder);
            // 这块不用加锁，因为在lua脚本判断过“一人一单”，并且判断过“库存充足”，库存充足且符合一人一单，程序才会跑到这里

            // 扣减库存
            UpdateWrapper<SeckillVoucher> updateWrapper = new UpdateWrapper<>();
            updateWrapper.setSql("stock = stock - 1");
            updateWrapper.eq("voucher_id", voucherId);
            updateWrapper.gt("stock", 0);
            seckillVoucherMapper.update(null, updateWrapper);

            // 订单入数据库
            voucherOrderMapper.insert(voucherOrder);

        } catch (Exception e) {
            e.printStackTrace();
            channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, true);
            log.info("接收信息异常！");
        }
    }
}
