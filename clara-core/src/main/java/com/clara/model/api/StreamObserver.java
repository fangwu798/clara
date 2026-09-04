package com.clara.model.api;

public interface StreamObserver {
    void onEvent(StreamEvent event);
}
