package com.clara.model.provider.http;

/**
 * 监听服务提供商SSE的生命周期事件。
 */
public interface ProviderSseListener {
    boolean onEvent(ProviderSseEvent event);
}
