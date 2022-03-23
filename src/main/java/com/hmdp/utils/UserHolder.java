package com.hmdp.utils;

import com.hmdp.dto.UserDTO;

/**
 * 从jdk的官方文档中的描述：
 * 1.ThreadLocal类是用来提供放线程内部的局部变量，这样变量在多线程环境下访问（通过set和get访问）时能保证各个线程间的变量相对独立于其他线程内的变量。
 * ThreadLocal实例通常来说都是private static 类型的，用来关联线程和线程上下文。
 * 2.我们可以得知ThreadLocal的作用是提供线程内的局部变量，不同的线程之间不会相互干扰。
 * 这种变量在线程的生命周期内起作用，减少同一个线程内多个函数或组件之间一些公共变量传递的复杂度。
 * 总结：线程并发，传递数据，线程隔离。
 *
 * 方法：
 * ThreadLocal() ：创建ThreadLocal对象
 * public void set(T value)：设置当前线程绑定的局部变量
 * public T get()：获取当前线程绑定的局部变量
 * public void remove()：移除当前线程绑定的局部变量
 *
 * 理解：
 * 底层是一个ThreadLocalMap。键默认是当前线程，值就是当前线程域中存入的东西。
 */
public class UserHolder {
    //ThreadLocal是线程域对象，默认操作的就是当前线程域中的数据，非公共的、私有域中的数据有效避免了线程安全问题。
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    public static UserDTO getUser(){
        return tl.get();
    }

    public static void removeUser(){
        tl.remove();
    }
}
