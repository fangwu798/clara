package com.clara.workflow.runtime;

import com.clara.core.model.IStreamObserver;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the thread-local channel in {@link WorkflowProgress}: nested scope restore, notice routing,
 * and idempotent close. A leaked observer would make the next turn render into the previous turn.
 */
public class WorkflowProgressTest {

    @After
    public void clearThreadLocal() {
        // Restore the unregistered state even when an assertion fails.
        try (WorkflowProgress.Scope ignored = WorkflowProgress.open(null, null)) {
            // open/close clears the current thread registration
        }
    }

    @Test
    public void nestedScopesRestoreOuterObserverAndNoticeChannel() {
        IStreamObserver outer = event -> { };
        IStreamObserver inner = event -> { };
        List<String> notices = new ArrayList<>();

        try (WorkflowProgress.Scope ignored = WorkflowProgress.open(outer, notices::add)) {
            assertSame(outer, WorkflowProgress.observer());
            try (WorkflowProgress.Scope nested = WorkflowProgress.open(inner, notices::add)) {
                assertSame(inner, WorkflowProgress.observer());
                WorkflowProgress.notice("inner");
            }
            assertSame("inner scope must restore the outer observer", outer, WorkflowProgress.observer());
            WorkflowProgress.notice("outer");
        }

        assertNull("outermost close must clear the observer", WorkflowProgress.observer());
        assertEquals(2, notices.size());
        assertEquals("inner", notices.get(0));
        assertEquals("outer", notices.get(1));
    }

    @Test
    public void noticeFallsBackToStdoutWhenChannelMissing() {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, "UTF-8"));
            WorkflowProgress.notice("notice-without-channel");
        } catch (Exception failure) {
            throw new AssertionError(failure);
        } finally {
            System.setOut(original);
        }
        String text = new String(captured.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(text.contains("notice-without-channel"));
    }

    @Test
    public void scopeCloseIsIdempotent() {
        WorkflowProgress.Scope scope = WorkflowProgress.open(event -> { }, text -> { });
        scope.close();
        scope.close();
        assertNull(WorkflowProgress.observer());
    }

    @Test
    public void otherThreadsDoNotSeeCurrentScope() throws Exception {
        try (WorkflowProgress.Scope ignored = WorkflowProgress.open(event -> { }, text -> { })) {
            final IStreamObserver[] seen = new IStreamObserver[1];
            Thread worker = new Thread(() -> seen[0] = WorkflowProgress.observer(), "progress-worker");
            worker.start();
            worker.join();
            assertNull("thread-local channel must not leak into parallel branches", seen[0]);
        }
    }
}
