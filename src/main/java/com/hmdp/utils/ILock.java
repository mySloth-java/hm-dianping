package com.hmdp.utils;

/**
 * @author cgJavaAfter
 * @date 2023-03-06 16:44
 */
public interface ILock {

    boolean tryLock(long timeOut);
    void unLock();
}
