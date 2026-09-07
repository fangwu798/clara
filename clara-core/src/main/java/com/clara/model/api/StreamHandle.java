package com.clara.model.api;

/**
 * 控制并观察正在执行的流。
 */
public interface StreamHandle {
    boolean cancel();
    boolean isDone();
}
