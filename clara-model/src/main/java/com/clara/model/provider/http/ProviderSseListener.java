package com.clara.model.provider.http;

public interface ProviderSseListener {
    boolean onEvent(ProviderSseEvent event);
}
