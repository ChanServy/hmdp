package com.hmdp.pattern;

import java.io.Serializable;

/**
 * @author CHAN
 * @since 2022/8/15
 */
public class Singleton8 implements Serializable {
    private Singleton8() {

    }

    private static class Handler {
        static Singleton8 INSTANCE = new Singleton8();
    }

    public static Singleton8 getInstance() {
        return Handler.INSTANCE;
    }

    public Object readResolve() {
        return Handler.INSTANCE;
    }
}
