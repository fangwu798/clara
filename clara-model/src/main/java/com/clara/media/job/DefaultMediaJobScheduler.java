package com.clara.media.job;

import com.clara.exception.ClaraError;
import com.clara.exception.ClaraException;
import com.clara.exception.ErrorCategory;
import com.clara.exception.ErrorCodes;
import com.clara.media.MediaAsset;
import com.clara.media.MediaStore;
import com.clara.media.MediaUpload;
import java.util.Collections;
import java.util.List;

/**
 * 编排异步媒体任务的提交、轮询、取消和已验证回调，并持久化每次状态迁移。
 *
 * <p>常规用法：调用 {@link #submit} 创建任务，定时使用 {@link #due} 找出到期任务并调用 {@link #poll}；
 * 主动取消使用 {@link #cancel}，服务提供商回调必须先完成安全校验再调用 {@link #applyVerifiedCallback}。
 *
 * <p>注意：写操作必须携带当前 expected version，失败时应重新读取而不是盲目重试。提交方需提供稳定的
 * 幂等键；只有状态机允许的迁移才会落库，成功结果引用会先通过媒体存储桥接为 Clara 资产。
 */
public final class DefaultMediaJobScheduler implements MediaJobScheduler {
    private final MediaJobStore store;
    private final MediaJobStateMachine stateMachine;
    private final MediaStore mediaStore;
    private final MediaJobSchedulerConfig config;

    public DefaultMediaJobScheduler(MediaJobStore store, MediaStore mediaStore,
            MediaJobSchedulerConfig config) {
        if (store == null || mediaStore == null || config == null) {
            throw new IllegalArgumentException("dependencies must not be null");
        }
        this.store = store;
        this.stateMachine = new MediaJobStateMachine(store);
        this.mediaStore = mediaStore;
        this.config = config;
    }

    public MediaJob submit(String tenantId, String jobId, String idempotencyKey, long deadlineAt,
            MediaJobProviderOperation operation, long now) {
        if (operation == null) { throw new IllegalArgumentException("operation must not be null"); }
        MediaJob job = stateMachine.createScheduled(jobId, tenantId, idempotencyKey, deadlineAt, now);
        if (job.getStatus().isTerminal() || job.getProviderJobId() != null) return job;
        if (job.isSubmissionAttempted() && !operation.supportsIdempotentSubmission()) submissionUnknown();
        MediaJob marked = stateMachine.transition(tenantId, job.getJobId(), job.getVersion(),
                MediaJobStatus.QUEUED, new MediaJobUpdate(null, null, null, null,
                        Boolean.TRUE, null, null), now);
        String providerJobId;
        try {
            providerJobId = operation.submit(idempotencyKey);
        } catch (MediaJobProviderException failure) {
            if (failure.isSideEffectUnknown()) submissionUnknown();
            if (failure.isRetryable() && operation.supportsIdempotentSubmission()) providerFailure(true);
            return failed(marked, ErrorCodes.MEDIA_JOB_PROVIDER_FAILED, now);
        } catch (RuntimeException unknown) {
            submissionUnknown();
            return null;
        }
        if (providerJobId == null || providerJobId.trim().isEmpty())
            return failed(marked, ErrorCodes.MEDIA_JOB_PROVIDER_FAILED, now);
        return stateMachine.transition(tenantId, marked.getJobId(), marked.getVersion(), MediaJobStatus.RUNNING,
                new MediaJobUpdate(providerJobId, Integer.valueOf(0), null, null, Boolean.TRUE,
                        Integer.valueOf(0), Long.valueOf(safeAdd(now, config.getInitialPollDelayMs()))), now);
    }

    public MediaJob poll(String tenantId, String jobId, long expectedVersion,
            MediaJobProviderOperation operation, long now) {
        if (operation == null) { throw new IllegalArgumentException("operation must not be null"); }
        MediaJob current = stateMachine.get(tenantId, jobId);
        if (current.getVersion() != expectedVersion) conflict();
        if (current.getStatus().isTerminal()) return current;
        if (current.getStatus() != MediaJobStatus.RUNNING || current.getProviderJobId() == null)
            invalidState();
        if (current.getDeadlineAt() != null && now >= current.getDeadlineAt().longValue())
            return expire(current, operation, now);
        if (current.getNextPollAt() != null && now < current.getNextPollAt().longValue()) return current;
        MediaProviderJobSnapshot snapshot;
        try { snapshot = operation.poll(current.getProviderJobId()); }
        catch (MediaJobProviderException failure) {
            if (!failure.isRetryable()) return failed(current, ErrorCodes.MEDIA_JOB_PROVIDER_FAILED, now);
            return retryOrFail(current, operation, now);
        } catch (RuntimeException failure) {
            return failed(current, ErrorCodes.MEDIA_JOB_PROVIDER_FAILED, now);
        }
        if (snapshot == null) return failed(current, ErrorCodes.MEDIA_JOB_PROVIDER_FAILED, now);
        if (snapshot.getStatus() == MediaProviderJobStatus.SUCCEEDED) return succeed(current, snapshot, now);
        if (snapshot.getStatus() == MediaProviderJobStatus.FAILED)
            return failed(current, ErrorCodes.MEDIA_JOB_PROVIDER_FAILED, now);
        if (snapshot.getStatus() == MediaProviderJobStatus.CANCELLED)
            return stateMachine.transition(tenantId, jobId, current.getVersion(), MediaJobStatus.CANCELLED,
                    MediaJobUpdate.none(), now);
        return running(current, snapshot.getProgress(), operation, now);
    }

    public MediaJob cancel(String tenantId, String jobId, long expectedVersion,
            MediaJobProviderOperation operation, long now) {
        if (operation == null) { throw new IllegalArgumentException("operation must not be null"); }
        MediaJob current = stateMachine.get(tenantId, jobId);
        if (current.getVersion() != expectedVersion) conflict();
        if (current.getStatus().isTerminal()) return current;
        MediaJob cancelled = stateMachine.transition(tenantId, jobId, expectedVersion,
                MediaJobStatus.CANCELLED, MediaJobUpdate.none(), now);
        if (current.getProviderJobId() != null) {
            try { operation.cancel(current.getProviderJobId()); }
            catch (RuntimeException ignored) { }
        }
        return cancelled;
    }

    public MediaJob applyVerifiedCallback(String tenantId, String jobId, long expectedVersion,
            MediaProviderJobSnapshot snapshot, long now) {
        if (snapshot == null) { throw new IllegalArgumentException("snapshot must not be null"); }
        MediaJob current = stateMachine.get(tenantId, jobId);
        if (current.getVersion() != expectedVersion) conflict();
        if (current.getStatus().isTerminal()) return current;
        if (current.getStatus() != MediaJobStatus.RUNNING) invalidState();
        if (current.getDeadlineAt() != null && now >= current.getDeadlineAt().longValue())
            return stateMachine.transition(tenantId, jobId, expectedVersion,
                    MediaJobStatus.EXPIRED, MediaJobUpdate.none(), now);
        if (snapshot.getStatus() == MediaProviderJobStatus.SUCCEEDED) return succeed(current, snapshot, now);
        if (snapshot.getStatus() == MediaProviderJobStatus.FAILED)
            return failed(current, ErrorCodes.MEDIA_JOB_PROVIDER_FAILED, now);
        if (snapshot.getStatus() == MediaProviderJobStatus.CANCELLED)
            return stateMachine.transition(tenantId, jobId, expectedVersion,
                    MediaJobStatus.CANCELLED, MediaJobUpdate.none(), now);
        return stateMachine.transition(tenantId, jobId, expectedVersion, MediaJobStatus.RUNNING,
                new MediaJobUpdate(null, Integer.valueOf(Math.max(current.getProgress(), snapshot.getProgress())),
                        null, null), now);
    }

    public List<MediaJob> due(long now, int limit) { return store.findDue(now, limit); }

    private MediaJob running(MediaJob current, int providerProgress,
            MediaJobProviderOperation operation, long now) {
        int attempts = current.getPollAttempts() + 1;
        if (attempts >= config.getMaxPollAttempts()) {
            MediaJob failed = failed(current, ErrorCodes.MEDIA_JOB_POLL_LIMIT, now);
            try { operation.cancel(current.getProviderJobId()); } catch (RuntimeException ignored) { }
            return failed;
        }
        long next = safeAdd(now, delay(attempts));
        if (current.getDeadlineAt() != null && next >= current.getDeadlineAt().longValue())
            next = current.getDeadlineAt().longValue();
        int progress = Math.max(current.getProgress(), providerProgress);
        return stateMachine.transition(current.getTenantId(), current.getJobId(), current.getVersion(),
                MediaJobStatus.RUNNING, new MediaJobUpdate(null, Integer.valueOf(progress), null, null,
                        null, Integer.valueOf(attempts), Long.valueOf(next)), now);
    }
    private MediaJob retryOrFail(MediaJob current, MediaJobProviderOperation operation, long now) {
        return running(current, current.getProgress(), operation, now);
    }
    private MediaJob succeed(MediaJob current, MediaProviderJobSnapshot snapshot, long now) {
        MediaUpload output = snapshot.getOutput();
        if (!current.getTenantId().equals(output.getTenantId())) invalidState();
        MediaAsset asset = mediaStore.put(output);
        return stateMachine.transition(current.getTenantId(), current.getJobId(), current.getVersion(),
                MediaJobStatus.SUCCEEDED, new MediaJobUpdate(null, Integer.valueOf(100), null,
                        asset.getAssetId()), now);
    }
    private MediaJob expire(MediaJob current, MediaJobProviderOperation operation, long now) {
        MediaJob expired = stateMachine.transition(current.getTenantId(), current.getJobId(), current.getVersion(),
                MediaJobStatus.EXPIRED, MediaJobUpdate.none(), now);
        try { operation.cancel(current.getProviderJobId()); } catch (RuntimeException ignored) { }
        return expired;
    }
    private MediaJob failed(MediaJob current, String code, long now) {
        String safeCode = code == null || code.trim().isEmpty() ? ErrorCodes.MEDIA_JOB_PROVIDER_FAILED : code;
        return stateMachine.transition(current.getTenantId(), current.getJobId(), current.getVersion(),
                MediaJobStatus.FAILED, new MediaJobUpdate(null, null, safeCode, null), now);
    }
    private long delay(int attempts) {
        double delay = config.getInitialPollDelayMs();
        for (int i = 0; i < attempts; i++) {
            delay *= config.getBackoffMultiplier();
            if (delay >= config.getMaxPollDelayMs()) return config.getMaxPollDelayMs();
        }
        return Math.min(config.getMaxPollDelayMs(), Math.max(1L, Math.round(delay)));
    }
    private static long safeAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
    private static void conflict() { fail(ErrorCodes.MEDIA_JOB_VERSION_CONFLICT, ErrorCategory.CONFLICT, false); }
    private static void invalidState() { fail(ErrorCodes.MEDIA_JOB_INVALID_STATE, ErrorCategory.CONFLICT, false); }
    private static void submissionUnknown() { fail(ErrorCodes.MEDIA_JOB_SUBMISSION_UNKNOWN, ErrorCategory.UNKNOWN_SIDE_EFFECT, false); }
    private static void providerFailure(boolean retryable) { fail(ErrorCodes.MEDIA_JOB_PROVIDER_FAILED, ErrorCategory.EXTERNAL_SERVICE, retryable); }
    private static void fail(String code, ErrorCategory category, boolean retryable) {
        throw new ClaraException(new ClaraError(code, category, code, retryable, null, null, null, null,
                Collections.<String, String>emptyMap()));
    }
}
