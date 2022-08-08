package com.hmdp.pattern;

import java.io.Serializable;

/**
 * @author CHAN
 * @since 2022/8/7
 */
public class Singleton3 implements Serializable {
    private Singleton3() {
        if (INSTANCE != null) {
            throw new RuntimeException("已经创建过了！");
        }
    }

    private static final Singleton3 INSTANCE = new Singleton3();

    public static Singleton3 getInstance() {
        return INSTANCE;
    }

    public Object readResolve() {
        return INSTANCE;
    }
}
