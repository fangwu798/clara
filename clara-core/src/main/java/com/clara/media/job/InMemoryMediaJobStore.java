package com.clara.media.job;

import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 提供媒体任务存储器的内存实现。
 */
public final class InMemoryMediaJobStore implements MediaJobStore {
    private final Map<String, MediaJob> jobs = new HashMap<String, MediaJob>();
    private final Map<String, String> idempotencyIndex = new HashMap<String, String>();

    public synchronized void create(MediaJob job) {
        if (job == null) { throw new IllegalArgumentException("job must not be null"); }
        String key = key(job.getTenantId(), job.getJobId());
        if (jobs.containsKey(key)) { throw new IllegalStateException("job already exists"); }
        if (job.getIdempotencyKey() != null) {
            String idempotency = key(job.getTenantId(), job.getIdempotencyKey());
            if (idempotencyIndex.containsKey(idempotency)) { throw new IllegalStateException("idempotency key already exists"); }
            idempotencyIndex.put(idempotency, job.getJobId());
        }
        jobs.put(key, job);
    }
    public synchronized MediaJob get(String tenantId, String jobId) {
        return jobs.get(key(tenantId, jobId));
    }
    public synchronized MediaJob findByIdempotencyKey(String tenantId, String idempotencyKey) {
        String jobId = idempotencyIndex.get(key(tenantId, idempotencyKey));
        return jobId == null ? null : get(tenantId, jobId);
    }
    public synchronized List<MediaJob> findDue(long now, int limit) {
        if (limit <= 0) { throw new IllegalArgumentException("limit must be positive"); }
        List<MediaJob> due = new ArrayList<MediaJob>();
        for (MediaJob job : jobs.values()) if (job.getStatus() == MediaJobStatus.RUNNING
                && job.getNextPollAt() != null && job.getNextPollAt().longValue() <= now) due.add(job);
        Collections.sort(due, new Comparator<MediaJob>() {
            public int compare(MediaJob left, MediaJob right) {
                int time = Long.compare(left.getNextPollAt().longValue(), right.getNextPollAt().longValue());
                return time != 0 ? time : left.getJobId().compareTo(right.getJobId());
            }
        });
        if (due.size() > limit) due = new ArrayList<MediaJob>(due.subList(0, limit));
        return Collections.unmodifiableList(due);
    }
    public synchronized boolean replace(String tenantId, String jobId, long expectedVersion, MediaJob replacement) {
        String key = key(tenantId, jobId);
        MediaJob current = jobs.get(key);
        if (current == null || current.getVersion() != expectedVersion) return false;
        if (replacement == null || !tenantId.equals(replacement.getTenantId()) || !jobId.equals(replacement.getJobId())) {
            throw new IllegalArgumentException("replacement identity differs");
        }
        if (!Objects.equals(current.getIdempotencyKey(), replacement.getIdempotencyKey())
                || !Objects.equals(current.getDeadlineAt(), replacement.getDeadlineAt())) {
            throw new IllegalArgumentException("immutable scheduling identity differs");
        }
        jobs.put(key, replacement);
        return true;
    }
    private static String key(String tenantId, String jobId) {
        if (tenantId == null || jobId == null) { throw new IllegalArgumentException("identity must not be null"); }
        return tenantId.length() + ":" + tenantId + jobId;
    }
}
