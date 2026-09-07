package com.clara.model.api;

/**
 * 接收流产生的事件。
 */
public interface StreamObserver {
    void onEvent(StreamEvent event);
}
