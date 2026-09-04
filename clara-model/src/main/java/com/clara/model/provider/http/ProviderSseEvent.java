package com.clara.model.provider.http;

public final class ProviderSseEvent {
    private final String event;
    private final String data;
    private final String id;
    private final Long retryMillis;

    public ProviderSseEvent(String event, String data, String id, Long retryMillis) {
        this.event = event;
        this.data = data;
        this.id = id;
        this.retryMillis = retryMillis;
    }

    public String getEvent() { return event; }
    public String getData() { return data; }
    public String getId() { return id; }
    public Long getRetryMillis() { return retryMillis; }
}
