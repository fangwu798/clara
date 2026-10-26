package com.clara.workflow.runtime;

import com.clara.core.diagnostics.Diagnostics;
import com.clara.core.diagnostics.IDiagnosticsSink;
import com.clara.core.model.IStreamObserver;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

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
    public void noticeFallsBackToDiagnosticsWhenChannelMissing() {
        // 库模块不直接写标准输出：未注册通道时交给 Diagnostics 端口。
        final List<String> info = new ArrayList<>();
        Diagnostics.install(new IDiagnosticsSink() {
            @Override
            public void info(String message) {
                info.add(message);
            }

            @Override
            public void warn(String message) {
                // 本用例只关心进度通道
            }
        });
        try {
            WorkflowProgress.notice("notice-without-channel");
        } finally {
            Diagnostics.reset();
        }
        assertEquals(1, info.size());
        assertEquals("notice-without-channel", info.get(0));
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
