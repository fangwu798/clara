package com.clara.media.job;

import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCodes;
import com.clara.media.MediaAsset;
import com.clara.media.MediaInput;
import com.clara.media.MediaStore;
import com.clara.media.MediaType;
import com.clara.media.MediaUpload;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import org.junit.Test;
import static org.junit.Assert.*;

public class DefaultMediaJobSchedulerTest {
    @Test public void idempotentSubmissionReplayReturnsExistingJob() {
        InMemoryMediaJobStore jobs = new InMemoryMediaJobStore();
        DefaultMediaJobScheduler scheduler = scheduler(jobs, 4);
        FakeOperation operation = new FakeOperation(true);
        MediaJob first = scheduler.submit("tenant", "job-1", "key", 1000, operation, 0);
        MediaJob replay = scheduler.submit("tenant", "job-2", "key", 1000, operation, 1);
        assertEquals("job-1", replay.getJobId());
        assertEquals(first.getProviderJobId(), replay.getProviderJobId());
        assertEquals(1, operation.submits);
    }

    @Test public void nonIdempotentUnknownSubmissionCannotBeReplayed() {
        InMemoryMediaJobStore jobs = new InMemoryMediaJobStore();
        final DefaultMediaJobScheduler scheduler = scheduler(jobs, 4);
        final FakeOperation operation = new FakeOperation(false);
        operation.submitFailure = new MediaJobProviderException("timeout", true, true, null);
        assertCode(ErrorCodes.MEDIA_JOB_SUBMISSION_UNKNOWN, new Runnable() { public void run() {
            scheduler.submit("tenant", "job", "key", 1000, operation, 0);
        }});
        operation.submitFailure = null;
        assertCode(ErrorCodes.MEDIA_JOB_SUBMISSION_UNKNOWN, new Runnable() { public void run() {
            scheduler.submit("tenant", "job", "key", 1000, operation, 1);
        }});
        assertEquals(1, operation.submits);
    }

    @Test public void pollsWhenDueAndStoresOutputBeforeSuccess() {
        InMemoryMediaJobStore jobs = new InMemoryMediaJobStore();
        RecordingMediaStore media = new RecordingMediaStore();
        DefaultMediaJobScheduler scheduler = new DefaultMediaJobScheduler(jobs, media,
                new MediaJobSchedulerConfig(10, 100, 2, 4));
        FakeOperation operation = new FakeOperation(true);
        operation.snapshots.add(new MediaProviderJobSnapshot(MediaProviderJobStatus.RUNNING, 40, null, null));
        operation.snapshots.add(new MediaProviderJobSnapshot(MediaProviderJobStatus.SUCCEEDED, 100, null, output("tenant")));
        MediaJob running = scheduler.submit("tenant", "job", "key", 1000, operation, 0);
        assertTrue(scheduler.due(9, 10).isEmpty());
        assertEquals(1, scheduler.due(10, 10).size());
        MediaJob progressed = scheduler.poll("tenant", "job", running.getVersion(), operation, 10);
        assertEquals(40, progressed.getProgress());
        assertEquals(30L, progressed.getNextPollAt().longValue());
        assertSame(progressed, scheduler.poll("tenant", "job", progressed.getVersion(), operation, 20));
        MediaJob succeeded = scheduler.poll("tenant", "job", progressed.getVersion(), operation, 30);
        assertEquals(MediaJobStatus.SUCCEEDED, succeeded.getStatus());
        assertEquals(1, media.puts);
        assertEquals(2, operation.polls);
    }

    @Test public void cancellationWinsAndPollLimitCancelsProvider() {
        InMemoryMediaJobStore jobs = new InMemoryMediaJobStore();
        DefaultMediaJobScheduler scheduler = scheduler(jobs, 2);
        FakeOperation operation = new FakeOperation(true);
        MediaJob running = scheduler.submit("tenant", "cancel", "cancel-key", 1000, operation, 0);
        MediaJob cancelled = scheduler.cancel("tenant", "cancel", running.getVersion(), operation, 1);
        assertEquals(MediaJobStatus.CANCELLED, cancelled.getStatus());
        assertSame(cancelled, scheduler.poll("tenant", "cancel", cancelled.getVersion(), operation, 20));
        assertEquals(1, operation.cancels);
        assertEquals(0, operation.polls);

        FakeOperation retrying = new FakeOperation(true);
        retrying.pollFailure = new MediaJobProviderException("temporary", true, false, null);
        MediaJob polling = scheduler.submit("tenant", "limited", "limited-key", 1000, retrying, 0);
        MediaJob retry = scheduler.poll("tenant", "limited", polling.getVersion(), retrying, 10);
        MediaJob failed = scheduler.poll("tenant", "limited", retry.getVersion(), retrying,
                retry.getNextPollAt().longValue());
        assertEquals(MediaJobStatus.FAILED, failed.getStatus());
        assertEquals(ErrorCodes.MEDIA_JOB_POLL_LIMIT, failed.getErrorCode());
        assertEquals(1, retrying.cancels);
    }

    @Test public void verifiedCallbackStoresSuccessButLateCallbackAfterCancelIsIgnored() {
        InMemoryMediaJobStore jobs = new InMemoryMediaJobStore();
        RecordingMediaStore media = new RecordingMediaStore();
        DefaultMediaJobScheduler scheduler = new DefaultMediaJobScheduler(jobs, media,
                new MediaJobSchedulerConfig(10, 100, 2, 4));
        FakeOperation operation = new FakeOperation(true);
        MediaJob running = scheduler.submit("tenant", "success", "success-key", 1000, operation, 0);
        MediaJob succeeded = scheduler.applyVerifiedCallback("tenant", "success", running.getVersion(),
                new MediaProviderJobSnapshot(MediaProviderJobStatus.SUCCEEDED, 100, null, output("tenant")), 5);
        assertEquals(MediaJobStatus.SUCCEEDED, succeeded.getStatus());
        assertEquals(1, media.puts);

        MediaJob other = scheduler.submit("tenant", "cancelled", "cancelled-key", 1000, operation, 0);
        MediaJob cancelled = scheduler.cancel("tenant", "cancelled", other.getVersion(), operation, 1);
        MediaJob ignored = scheduler.applyVerifiedCallback("tenant", "cancelled", cancelled.getVersion(),
                new MediaProviderJobSnapshot(MediaProviderJobStatus.SUCCEEDED, 100, null, output("tenant")), 5);
        assertSame(cancelled, ignored);
        assertEquals(1, media.puts);
    }

    private static DefaultMediaJobScheduler scheduler(InMemoryMediaJobStore jobs, int attempts) {
        return new DefaultMediaJobScheduler(jobs, new RecordingMediaStore(),
                new MediaJobSchedulerConfig(10, 100, 2, attempts));
    }
    private static MediaUpload output(final String tenant) {
        return new MediaUpload(tenant, MediaType.VIDEO, "output.mp4", "video/mp4", null,
                null, null, Long.valueOf(100), null, Collections.<String, String>emptyMap(),
                new MediaInput() { public InputStream open() { return new ByteArrayInputStream(new byte[] {1}); } });
    }
    private static void assertCode(String code, Runnable action) {
        try { action.run(); fail("expected " + code); }
        catch (ClaraException e) { assertEquals(code, e.getError().getCode()); }
    }
    private static final class FakeOperation implements MediaJobProviderOperation {
        private final boolean idempotent;
        private final Deque<MediaProviderJobSnapshot> snapshots = new ArrayDeque<MediaProviderJobSnapshot>();
        private MediaJobProviderException submitFailure;
        private MediaJobProviderException pollFailure;
        private int submits;
        private int polls;
        private int cancels;
        private FakeOperation(boolean idempotent) { this.idempotent = idempotent; }
        public boolean supportsIdempotentSubmission() { return idempotent; }
        public String submit(String key) { submits++; if (submitFailure != null) throw submitFailure; return "provider-" + key; }
        public MediaProviderJobSnapshot poll(String id) { polls++; if (pollFailure != null) throw pollFailure; return snapshots.removeFirst(); }
        public void cancel(String id) { cancels++; }
    }
    private static final class RecordingMediaStore implements MediaStore {
        private int puts;
        public MediaAsset put(MediaUpload upload) {
            puts++;
            String id = repeat('a', 64);
            return new MediaAsset(id, upload.getMediaType(), upload.getMimeType(), 1, id,
                    "memory:" + id, null, upload.getWidth(), upload.getHeight(), upload.getDurationMs(),
                    upload.getTenantId(), 1, null, upload.getMetadata());
        }
        public MediaAsset get(String tenant, String asset) { throw new UnsupportedOperationException(); }
        public InputStream open(String tenant, String asset) { throw new UnsupportedOperationException(); }
        public void delete(String tenant, String asset) { throw new UnsupportedOperationException(); }
        private static String repeat(char value, int count) { char[] chars = new char[count]; java.util.Arrays.fill(chars, value); return new String(chars); }
    }
}
