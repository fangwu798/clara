package com.clara.media.job;

public interface MediaJobProviderOperation {
    boolean supportsIdempotentSubmission();
    String submit(String idempotencyKey);
    MediaProviderJobSnapshot poll(String providerJobId);
    void cancel(String providerJobId);
}
