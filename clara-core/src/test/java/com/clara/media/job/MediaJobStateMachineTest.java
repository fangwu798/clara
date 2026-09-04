package com.clara.media.job;

import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCodes;
import org.junit.Test;
import static org.junit.Assert.*;

public class MediaJobStateMachineTest {
    @Test public void appliesVersionedForwardTransitions() {
        MediaJobStateMachine machine = new MediaJobStateMachine(new InMemoryMediaJobStore());
        MediaJob queued = machine.create("job", "tenant", 10);
        MediaJob running = machine.transition("tenant", "job", queued.getVersion(), MediaJobStatus.RUNNING,
                new MediaJobUpdate("provider-1", Integer.valueOf(40), null, null), 20);
        assertEquals(1, running.getVersion());
        assertEquals("provider-1", running.getProviderJobId());
        MediaJob progressed = machine.transition("tenant", "job", running.getVersion(), MediaJobStatus.RUNNING,
                new MediaJobUpdate(null, Integer.valueOf(80), null, null), 25);
        MediaJob succeeded = machine.transition("tenant", "job", progressed.getVersion(), MediaJobStatus.SUCCEEDED,
                new MediaJobUpdate(null, null, null, "asset"), 30);
        assertEquals(MediaJobStatus.SUCCEEDED, succeeded.getStatus());
        assertEquals(100, succeeded.getProgress());
        assertCode(ErrorCodes.MEDIA_JOB_INVALID_STATE, new Runnable() { public void run() {
            machine.transition("tenant", "job", 3, MediaJobStatus.RUNNING, MediaJobUpdate.none(), 40);
        }});
    }

    @Test public void rejectsStaleVersionsRegressionsAndIncompleteTerminalData() {
        final MediaJobStateMachine machine = new MediaJobStateMachine(new InMemoryMediaJobStore());
        machine.create("job", "tenant", 10);
        assertCode(ErrorCodes.MEDIA_JOB_VERSION_CONFLICT, new Runnable() { public void run() {
            machine.create("job", "tenant", 11);
        }});
        assertCode(ErrorCodes.MEDIA_JOB_VERSION_CONFLICT, new Runnable() { public void run() {
            machine.transition("tenant", "job", 3, MediaJobStatus.RUNNING, MediaJobUpdate.none(), 20);
        }});
        MediaJob running = machine.transition("tenant", "job", 0, MediaJobStatus.RUNNING,
                new MediaJobUpdate(null, Integer.valueOf(50), null, null), 20);
        assertCode(ErrorCodes.MEDIA_JOB_INVALID_STATE, new Runnable() { public void run() {
            machine.transition("tenant", "job", 1, MediaJobStatus.RUNNING,
                    new MediaJobUpdate(null, Integer.valueOf(40), null, null), 30);
        }});
        assertCode(ErrorCodes.MEDIA_JOB_INVALID_STATE, new Runnable() { public void run() {
            machine.transition("tenant", "job", 1, MediaJobStatus.FAILED, MediaJobUpdate.none(), 30);
        }});
        assertEquals(1, running.getVersion());
    }

    @Test public void permitsQueuedCancellationAndIsolatesTenants() {
        final MediaJobStateMachine machine = new MediaJobStateMachine(new InMemoryMediaJobStore());
        machine.create("job", "tenant-a", 10);
        assertEquals(MediaJobStatus.CANCELLED, machine.transition("tenant-a", "job", 0,
                MediaJobStatus.CANCELLED, MediaJobUpdate.none(), 11).getStatus());
        assertCode(ErrorCodes.MEDIA_JOB_NOT_FOUND, new Runnable() { public void run() { machine.get("tenant-b", "job"); }});
    }

    private static void assertCode(String code, Runnable action) {
        try { action.run(); fail("expected " + code); }
        catch (ClaraException e) { assertEquals(code, e.getError().getCode()); }
    }
}
