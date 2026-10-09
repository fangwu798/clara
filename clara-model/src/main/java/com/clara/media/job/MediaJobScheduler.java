package com.clara.media.job;

import java.util.List;

/**
 * 调度并跟踪媒体任务。
 */
public interface MediaJobScheduler {
    MediaJob submit(String tenantId, String jobId, String idempotencyKey, long deadlineAt,
            MediaJobProviderOperation operation, long now);
    MediaJob poll(String tenantId, String jobId, long expectedVersion,
            MediaJobProviderOperation operation, long now);
    MediaJob cancel(String tenantId, String jobId, long expectedVersion,
            MediaJobProviderOperation operation, long now);
    MediaJob applyVerifiedCallback(String tenantId, String jobId, long expectedVersion,
            MediaProviderJobSnapshot snapshot, long now);
    List<MediaJob> due(long now, int limit);
}
