package com.hmdp.pattern;

import java.io.Serializable;

/**
 * @author CHAN
 * @since 2022/8/7
 */
public class Singleton5 implements Serializable {
    private Singleton5() {
    }

    private static class Handler {
        static Singleton5 INSTANCE = new Singleton5();
    }

    public static Singleton5 getInstance() {
        return Handler.INSTANCE;
    }
}
