package com.hmdp.interceptor;

import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * @author CHAN
 * @since 2022-03-23
 */
public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //获取session
        HttpSession session = request.getSession();
        //获取session中的用户
        UserDTO user = (UserDTO) session.getAttribute("user");
        //判断用户是否存在
        if (user == null) {
            //用户不存在，没登录或者登录状态过期
            response.setStatus(401);
            return false;
        }
        //存在，保存用户到ThreadLocal，在拦截器中将user放入ThreadLocal，方便后续业务中获取用户信息
        UserHolder.saveUser(user);
        //放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
