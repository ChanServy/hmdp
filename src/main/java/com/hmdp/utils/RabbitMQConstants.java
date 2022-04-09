package com.hmdp.utils;

/**
 * @author CHAN
 * @since 2022/4/9
 */
public class RabbitMQConstants {
    // 异步创建订单，消息队列
    public static final String ASYNC_ORDER_QUEUE = "create.order.queue";
    // 异步创建订单，消息交换机
    public static final String ASYNC_ORDER_EXCHANGE = "create.order.exchange";
    // 异步创建订单，绑定标识
    public static final String ASYNC_CREATE_ORDER_KEY = "update.stock.create.order";
}
