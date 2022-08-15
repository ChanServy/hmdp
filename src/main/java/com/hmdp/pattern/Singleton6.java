package com.hmdp.pattern;

import java.io.Serializable;

/**
 * 单例饿汉式
 * @author CHAN
 * @since 2022/8/15
 */
public class Singleton6 implements Serializable {

    private Singleton6() {
        if (INSTANCE != null) {
            throw new RuntimeException("已经创建过了！");
        }
    }

    private static final Singleton6 INSTANCE = new Singleton6();

    public static Singleton6 getInstance() {
        return INSTANCE;
    }

    public Object readResolve() {
        return INSTANCE;
    }
}
