-- redis锁的key，这里作为参数在java程序中传过来
local key = KEYS[1];
-- 当前线程标识，这里作为参数在java程序中获取到传过来
local threadId = ARGV[1];
-- 获取锁中的线程标识，redis中的
local id = redis.call('get', key);
-- 比较当前线程标识与锁中的线程标识是否一致
if id == threadId then
    -- 一致，释放锁
    return redis.call('del', key);
end
-- 不一致，什么都不做
return 0;