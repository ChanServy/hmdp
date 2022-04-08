--- 参数列表
--- 优惠券id
local voucherId = ARGV[1];
--- 用户id
local userId = ARGV[2];
--- 数据key
--- 库存key：id为voucherId的优惠券库存
local stockKey = "seckill:stock:"..voucherId;
--- 订单key：id为voucherId的优惠券被哪些userId下了订单
local orderKey = "seckill:order:"..voucherId;
--- 脚本业务
--- 判断库存是否充足
if (tonumber(redis.call('get', stockKey)) <= 0) then
    --- 库存不足，返回1
    return 1;
end
--- 判断用户是否下过单
if (redis.call('sismember', orderKey, userId) == 1) then
    --- 存在，说明是重复下单，返回2
    return 2;
end
--- 符合下单条件
--- 扣减库存
redis.call('incrby', stockKey, -1);
--- 保存下单的用户id
redis.call('sadd', orderKey, userId)
return 0;