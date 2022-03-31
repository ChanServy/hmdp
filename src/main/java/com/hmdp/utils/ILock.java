package com.hmdp.utils;

/**
 * @author CHAN
 * @since 2022-03-31
 */
public interface ILock {
    boolean getLock(long timeoutSec);
    void releaseLock();
}
