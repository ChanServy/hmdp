package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private UserMapper userMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号格式
        if (RegexUtils.isPhoneInvalid(phone)) {
            //如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        //符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        //保存验证码到redis，以手机号作为键，保证唯一性，并设置验证码的有效期
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //发送验证码，这里模拟发送
        log.debug("发送短信验证码成功，验证码：{}", code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        //从redis中获取验证码并校验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (cacheCode == null || !cacheCode.equals(loginForm.getCode())) {
            return Result.fail("验证码错误");
        }
        //根据手机号查询用户
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.eq("phone", phone);
        User user = userMapper.selectOne(wrapper);
        //判断用户是否存在
        if (user == null) {
            //不存在，相当于注册。创建新用户并入库，并返回user
            user = createUserWithPhone(phone);
        }
        //随机生成一个token凭证
        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //因为我们用的是stringRedisTemplate，因此以hash类型存入redis中时，redis值部分的hash中的hashKey和value都需要是string
        //但是UserDto类中的id不是string类型，这样存入redis中的时候会报错，因此在将userDto放入map的时候要转换一下。
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())
        );
        //将user存入redis，以随机生成的token作为键
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        //设置token的有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        //返回token
        return Result.ok(token);
        //之前在用session实现的时候，我们不需要给前端返回什么，但在用redis实现的时候我们给前端返回了token。原因如下：
        //因为之前使用session做登录校验，每一个不同的浏览器在发送请求时都会携带cookie并且都有一个独立的session。
        //tomcat会自动将你的sessionId写到浏览器的cookie，以后每次请求都带着cookie，从而也就带着sessionId，从而
        //获取到session，我们之前将信息存入到session，因此也可以获取到，所以这里的sessionId就相当于一个登录凭证。
        //sessionId是由tomcat维护的。因此我们不需要给前端返回什么了。但是因为多台tomcat不会共享session存储空间，
        //我们的服务如果是集群式部署，那就是多台tomcat，当请求切换到不同的tomcat时会导致数据丢失的问题。
        //因此我们改用redis来实现登录校验。不使用session那套了，我们自己在服务端随机生成一个token，作为凭证，结合redis来实现登录校验。
        //浏览器不会自动帮我们将token写入浏览器的cookie，因此需要我们手动将token写回前端浏览器，前端开发人员将token保存到请求头，
        //这样以后每次请求都会携带token，然后在服务端的请求拦截器中，我们基于token到redis中查询用户信息得到登录状态，实现登录校验。
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(9));
        user.setPhone(phone);
        userMapper.insert(user);
        return user;
    }
}
