package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

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

    @Autowired
    private UserMapper userMapper;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号格式
        if (RegexUtils.isPhoneInvalid(phone)) {
            //如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        //符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        //保存手机号到session
        session.setAttribute("phone", phone);
        //保存验证码到session
        session.setAttribute("code", code);
        //发送验证码
        //TODO 调用阿里云的三方工具
        log.debug("发送短信验证码成功，验证码：{}", code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }
        String phoneFromSession = (String) session.getAttribute("phone");
        if (!phone.equals(phoneFromSession)) {
            return Result.fail("手机号不一致！");
        }
        //校验验证码
        String code = loginForm.getCode();
        String codeFromSession = (String) session.getAttribute("code");
        if (!code.equals(codeFromSession)) {
            return Result.fail("验证码错误！");
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
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //保存用户信息到session中
        session.setAttribute("user", userDTO);
        return Result.ok();
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(9));
        user.setPhone(phone);
        userMapper.insert(user);
        return user;
    }
}
