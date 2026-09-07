package com.clara.media.job;

/**
 * 定义媒体任务在服务提供商侧的提交与查询操作。
 */
public interface MediaJobProviderOperation {
    boolean supportsIdempotentSubmission();
    String submit(String idempotencyKey);
    MediaProviderJobSnapshot poll(String providerJobId);
    void cancel(String providerJobId);
}
