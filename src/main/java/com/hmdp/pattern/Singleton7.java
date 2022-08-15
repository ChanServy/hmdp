package com.hmdp.pattern;

import java.io.Serializable;

/**
 * 单例懒汉式
 * @author CHAN
 * @since 2022/8/15
 */
public class Singleton7 implements Serializable {
    private Singleton7() {
    }

    private static volatile Singleton7 INSTANCE = null;

    public static Singleton7 getInstance() {
        if (INSTANCE == null) {
            synchronized (Singleton7.class) {
                if (INSTANCE == null) {
                    INSTANCE = new Singleton7();
                }
            }
        }
        return INSTANCE;
    }

    public Object readResolve() {
        return INSTANCE;
    }
}
