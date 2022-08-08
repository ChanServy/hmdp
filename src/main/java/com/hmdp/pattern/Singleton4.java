package com.hmdp.pattern;

import java.io.Serializable;

/**
 * @author CHAN
 * @since 2022/8/7
 */
public class Singleton4 implements Serializable {
    private Singleton4() {
    }

    private static volatile Singleton4 INSTANCE = null;

    public static Singleton4 getInstance() {
        if (INSTANCE == null) {
            synchronized (Singleton4.class) {
                if (INSTANCE == null) {
                    INSTANCE = new Singleton4();
                }
            }
        }
        return INSTANCE;
    }
}
