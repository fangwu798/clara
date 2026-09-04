package com.clara.media.job.local;

import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCodes;
import com.clara.media.MediaAsset;
import com.clara.media.MediaStore;
import com.clara.media.MediaUpload;
import com.clara.media.job.DefaultMediaJobScheduler;
import com.clara.media.job.MediaJob;
import com.clara.media.job.MediaJobProviderOperation;
import com.clara.media.job.MediaJobSchedulerConfig;
import com.clara.media.job.MediaJobStatus;
import com.clara.media.job.MediaProviderJobSnapshot;
import com.clara.media.job.MediaProviderJobStatus;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class LocalMediaJobStoreTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    @Test public void restoresJobsVersionsAndTenantIdempotencyIndex() throws Exception {
        Path workspace = folder.newFolder("restore").toPath();
        LocalMediaJobStore store = new LocalMediaJobStore(config(workspace, "workspace-a", 10, 100000));
        MediaJob queued = MediaJob.queued("job-1", "tenant-a", "request-1", 10000L, 1000L);
        store.create(queued);
        MediaJob running = job("job-1", "tenant-a", MediaJobStatus.RUNNING, 1L,
                "provider-1", 25, null, null, 1000L, 2000L, "request-1", true, 2,
                Long.valueOf(3000L), Long.valueOf(10000L));
        assertTrue(store.replace("tenant-a", "job-1", 0L, running));
        store.close();

        LocalMediaJobStore reopened = new LocalMediaJobStore(config(workspace, "workspace-a", 10, 100000));
        MediaJob restored = reopened.get("tenant-a", "job-1");
        assertEquals(1L, restored.getVersion());
        assertEquals("provider-1", restored.getProviderJobId());
        assertEquals(2, restored.getPollAttempts());
        assertEquals("job-1", reopened.findByIdempotencyKey("tenant-a", "request-1").getJobId());
        assertNull(reopened.findByIdempotencyKey("tenant-b", "request-1"));
        assertFalse(reopened.replace("tenant-a", "job-1", 0L, running));
        reopened.close();
    }

    @Test public void schedulerContinuesPollingDueJobAfterStoreRestart() throws Exception {
        Path workspace = folder.newFolder("scheduler-restart").toPath();
        RestartOperation operation = new RestartOperation();
        LocalMediaJobStore first = new LocalMediaJobStore(config(workspace, "scheduler", 10, 100000));
        DefaultMediaJobScheduler firstScheduler = scheduler(first);
        MediaJob submitted = firstScheduler.submit("tenant", "job", "request", 1000L, operation, 0L);
        assertEquals(MediaJobStatus.RUNNING, submitted.getStatus());
        assertEquals(1, operation.submits);
        first.close();

        LocalMediaJobStore reopened = new LocalMediaJobStore(config(workspace, "scheduler", 10, 100000));
        DefaultMediaJobScheduler resumedScheduler = scheduler(reopened);
        List<MediaJob> due = resumedScheduler.due(10L, 10);
        assertEquals(1, due.size());
        MediaJob progressed = resumedScheduler.poll("tenant", "job", due.get(0).getVersion(),
                operation, 10L);
        assertEquals(MediaJobStatus.RUNNING, progressed.getStatus());
        assertEquals(50, progressed.getProgress());
        assertEquals(1, operation.submits);
        assertEquals(1, operation.polls);
        reopened.close();
    }

    @Test public void returnsDueJobsInStableTimeTenantAndJobOrder() throws Exception {
        Path workspace = folder.newFolder("due").toPath();
        LocalMediaJobStore store = new LocalMediaJobStore(config(workspace, "due", 10, 100000));
        store.create(job("z", "tenant-b", MediaJobStatus.RUNNING, 0, "p-z", 0,
                null, null, 100, 100, null, true, 0, Long.valueOf(500), Long.valueOf(1000)));
        store.create(job("b", "tenant-a", MediaJobStatus.RUNNING, 0, "p-b", 0,
                null, null, 100, 100, null, true, 0, Long.valueOf(400), Long.valueOf(1000)));
        store.create(job("a", "tenant-a", MediaJobStatus.RUNNING, 0, "p-a", 0,
                null, null, 100, 100, null, true, 0, Long.valueOf(400), Long.valueOf(1000)));
        store.create(MediaJob.queued("queued", "tenant-a", 100));

        List<MediaJob> due = store.findDue(500, 2);
        assertEquals(2, due.size());
        assertEquals("a", due.get(0).getJobId());
        assertEquals("b", due.get(1).getJobId());
        store.close();
    }

    @Test public void enforcesExclusiveWorkspaceLock() throws Exception {
        final Path workspace = folder.newFolder("lock").toPath();
        final LocalMediaJobStore first = new LocalMediaJobStore(config(workspace, "lock", 10, 100000));
        assertCode(ErrorCodes.MEDIA_JOB_STORE_LOCKED, new Runnable() { public void run() {
            new LocalMediaJobStore(config(workspace, "lock", 10, 100000));
        }});
        first.close();
    }

    @Test public void rejectsChecksumDamageAndWorkspaceMismatch() throws Exception {
        Path damagedWorkspace = folder.newFolder("damaged").toPath();
        LocalMediaJobStore damaged = new LocalMediaJobStore(config(damagedWorkspace, "damaged", 10, 100000));
        damaged.create(MediaJob.queued("job", "tenant", 100));
        damaged.close();
        Path snapshot = damagedWorkspace.resolve(".clara/state/media-jobs/jobs.bin");
        byte[] bytes = Files.readAllBytes(snapshot);
        bytes[bytes.length - 1] ^= 1;
        Files.write(snapshot, bytes);
        assertCode(ErrorCodes.MEDIA_JOB_STORE_CORRUPTED, new Runnable() { public void run() {
            new LocalMediaJobStore(config(damagedWorkspace, "damaged", 10, 100000));
        }});

        Path mismatchWorkspace = folder.newFolder("mismatch").toPath();
        LocalMediaJobStore original = new LocalMediaJobStore(config(mismatchWorkspace, "one", 10, 100000));
        original.create(MediaJob.queued("job", "tenant", 100));
        original.close();
        assertCode(ErrorCodes.MEDIA_JOB_STORE_CORRUPTED, new Runnable() { public void run() {
            new LocalMediaJobStore(config(mismatchWorkspace, "two", 10, 100000));
        }});
    }

    @Test public void rejectsJobCountAndSnapshotByteLimitsWithoutChangingMemory() throws Exception {
        Path countWorkspace = folder.newFolder("count").toPath();
        final LocalMediaJobStore countStore = new LocalMediaJobStore(config(countWorkspace, "count", 1, 100000));
        countStore.create(MediaJob.queued("one", "tenant", 100));
        assertCode(ErrorCodes.MEDIA_JOB_RESOURCE_LIMIT, new Runnable() { public void run() {
            countStore.create(MediaJob.queued("two", "tenant", 100));
        }});
        assertNull(countStore.get("tenant", "two"));
        countStore.close();

        Path bytesWorkspace = folder.newFolder("bytes").toPath();
        final LocalMediaJobStore byteStore = new LocalMediaJobStore(config(bytesWorkspace, "bytes", 10, 64));
        assertCode(ErrorCodes.MEDIA_JOB_RESOURCE_LIMIT, new Runnable() { public void run() {
            byteStore.create(MediaJob.queued("job", "tenant", 100));
        }});
        assertNull(byteStore.get("tenant", "job"));
        byteStore.close();
    }

    private static LocalMediaJobStoreConfig config(Path workspace, String workspaceId,
            int maxJobs, long maxFileBytes) {
        return new LocalMediaJobStoreConfig(workspace, workspaceId, maxJobs, maxFileBytes);
    }

    private static MediaJob job(String jobId, String tenantId, MediaJobStatus status, long version,
            String providerJobId, int progress, String errorCode, String outputAssetId,
            long createdAt, long updatedAt, String idempotencyKey, boolean submissionAttempted,
            int pollAttempts, Long nextPollAt, Long deadlineAt) {
        return new MediaJob(jobId, tenantId, status, version, providerJobId, progress, errorCode,
                outputAssetId, createdAt, updatedAt, idempotencyKey, submissionAttempted,
                pollAttempts, nextPollAt, deadlineAt);
    }

    private static DefaultMediaJobScheduler scheduler(LocalMediaJobStore store) {
        return new DefaultMediaJobScheduler(store, new NoopMediaStore(),
                new MediaJobSchedulerConfig(10L, 100L, 2.0d, 5));
    }

    private static void assertCode(String code, Runnable action) {
        try { action.run(); fail("expected " + code); }
        catch (ClaraException exception) { assertEquals(code, exception.getError().getCode()); }
    }

    private static final class RestartOperation implements MediaJobProviderOperation {
        private int submits;
        private int polls;
        public boolean supportsIdempotentSubmission() { return true; }
        public String submit(String idempotencyKey) { submits++; return "provider-job"; }
        public MediaProviderJobSnapshot poll(String providerJobId) {
            polls++;
            return new MediaProviderJobSnapshot(MediaProviderJobStatus.RUNNING, 50, null, null);
        }
        public void cancel(String providerJobId) { }
    }

    private static final class NoopMediaStore implements MediaStore {
        public MediaAsset put(MediaUpload upload) { throw new UnsupportedOperationException(); }
        public MediaAsset get(String tenantId, String assetId) { throw new UnsupportedOperationException(); }
        public InputStream open(String tenantId, String assetId) { throw new UnsupportedOperationException(); }
        public void delete(String tenantId, String assetId) { throw new UnsupportedOperationException(); }
    }
}
