package com.clara.media.job;

import java.util.List;

public interface MediaJobStore {
    void create(MediaJob job);
    MediaJob get(String tenantId, String jobId);
    MediaJob findByIdempotencyKey(String tenantId, String idempotencyKey);
    List<MediaJob> findDue(long now, int limit);
    boolean replace(String tenantId, String jobId, long expectedVersion, MediaJob replacement);
}
