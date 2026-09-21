package com.clara.media.job;

import com.clara.kernel.exception.ClaraError;
import com.clara.kernel.exception.ClaraException;
import com.clara.kernel.exception.ErrorCategory;
import com.clara.kernel.exception.ErrorCodes;
import java.util.Collections;

/**
 * 管理媒体任务的合法状态转换。
 */
public final class MediaJobStateMachine {
    private final MediaJobStore store;

    public MediaJobStateMachine(MediaJobStore store) {
        if (store == null) { throw new IllegalArgumentException("store must not be null"); }
        this.store = store;
    }
    public MediaJob create(String jobId, String tenantId, long now) {
        MediaJob job = MediaJob.queued(jobId, tenantId, now);
        try { store.create(job); }
        catch (IllegalStateException duplicate) {
            fail(ErrorCodes.MEDIA_JOB_VERSION_CONFLICT, ErrorCategory.CONFLICT);
        }
        return job;
    }
    public MediaJob createScheduled(String jobId, String tenantId, String idempotencyKey,
            long deadlineAt, long now) {
        MediaJob job = MediaJob.queued(jobId, tenantId, idempotencyKey, deadlineAt, now);
        MediaJob existing = store.findByIdempotencyKey(tenantId, idempotencyKey);
        if (existing != null) return existing;
        try { store.create(job); return job; }
        catch (IllegalStateException concurrent) {
            existing = store.findByIdempotencyKey(tenantId, idempotencyKey);
            if (existing != null) return existing;
            fail(ErrorCodes.MEDIA_JOB_VERSION_CONFLICT, ErrorCategory.CONFLICT);
            return null;
        }
    }
    public MediaJob get(String tenantId, String jobId) {
        MediaJob job = store.get(tenantId, jobId);
        if (job == null) fail(ErrorCodes.MEDIA_JOB_NOT_FOUND, ErrorCategory.VALIDATION);
        return job;
    }
    public MediaJob transition(String tenantId, String jobId, long expectedVersion,
            MediaJobStatus target, MediaJobUpdate update, long now) {
        if (target == null) { throw new IllegalArgumentException("target must not be null"); }
        if (update == null) update = MediaJobUpdate.none();
        MediaJob current = get(tenantId, jobId);
        if (current.getVersion() != expectedVersion)
            fail(ErrorCodes.MEDIA_JOB_VERSION_CONFLICT, ErrorCategory.CONFLICT);
        if (!allowed(current.getStatus(), target))
            fail(ErrorCodes.MEDIA_JOB_INVALID_STATE, ErrorCategory.CONFLICT);
        String provider = update.getProviderJobId() == null ? current.getProviderJobId() : update.getProviderJobId();
        if (current.getProviderJobId() != null && update.getProviderJobId() != null
                && !current.getProviderJobId().equals(update.getProviderJobId()))
            fail(ErrorCodes.MEDIA_JOB_INVALID_STATE, ErrorCategory.CONFLICT);
        int progress = update.getProgress() == null ? current.getProgress() : update.getProgress().intValue();
        if (progress < current.getProgress()) fail(ErrorCodes.MEDIA_JOB_INVALID_STATE, ErrorCategory.CONFLICT);
        if (target == MediaJobStatus.SUCCEEDED) progress = 100;
        String error = update.getErrorCode();
        String output = update.getOutputAssetId();
        if (target != MediaJobStatus.FAILED && error != null)
            fail(ErrorCodes.MEDIA_JOB_INVALID_STATE, ErrorCategory.CONFLICT);
        if (target != MediaJobStatus.SUCCEEDED && output != null)
            fail(ErrorCodes.MEDIA_JOB_INVALID_STATE, ErrorCategory.CONFLICT);
        boolean attempted = update.getSubmissionAttempted() == null ? current.isSubmissionAttempted()
                : update.getSubmissionAttempted().booleanValue();
        if (current.isSubmissionAttempted() && !attempted)
            fail(ErrorCodes.MEDIA_JOB_INVALID_STATE, ErrorCategory.CONFLICT);
        int polls = update.getPollAttempts() == null ? current.getPollAttempts() : update.getPollAttempts().intValue();
        if (polls < current.getPollAttempts()) fail(ErrorCodes.MEDIA_JOB_INVALID_STATE, ErrorCategory.CONFLICT);
        Long nextPollAt = update.getNextPollAt() == null ? current.getNextPollAt() : update.getNextPollAt();
        try {
            MediaJob next = new MediaJob(jobId, tenantId, target, expectedVersion + 1, provider,
                    progress, error, output, current.getCreatedAt(), now, current.getIdempotencyKey(), attempted,
                    polls, nextPollAt, current.getDeadlineAt());
            if (!store.replace(tenantId, jobId, expectedVersion, next))
                fail(ErrorCodes.MEDIA_JOB_VERSION_CONFLICT, ErrorCategory.CONFLICT);
            return next;
        } catch (IllegalArgumentException invalid) {
            fail(ErrorCodes.MEDIA_JOB_INVALID_STATE, ErrorCategory.CONFLICT);
            return null;
        }
    }
    private static boolean allowed(MediaJobStatus from, MediaJobStatus to) {
        if (from.isTerminal()) return false;
        if (from == MediaJobStatus.QUEUED)
            return to == MediaJobStatus.QUEUED || to == MediaJobStatus.RUNNING || to == MediaJobStatus.FAILED
                    || to == MediaJobStatus.CANCELLED || to == MediaJobStatus.EXPIRED;
        return from == MediaJobStatus.RUNNING && (to == MediaJobStatus.RUNNING || to.isTerminal());
    }
    private static void fail(String code, ErrorCategory category) {
        throw new ClaraException(new ClaraError(code, category, code, false, null, null, null, null,
                Collections.<String, String>emptyMap()));
    }
}
