package com.clara.skill.executor;

import com.clara.enums.skill.SkillExecutionStatusEnum;
import com.clara.exception.ClaraException;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Lifecycle state machine of the skill execution handle.
 */
public class SkillExecutionHandleTest {

    @Test
    public void startsRunningAndRejectsResultBeforeCompletion() {
        SkillExecutionHandle handle = new SkillExecutionHandle();
        assertEquals(SkillExecutionStatusEnum.RUNNING, handle.status());
        assertNull(handle.result());
        assertFalse(handle.await(10L, TimeUnit.MILLISECONDS));
        try {
            handle.resultOrThrow();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("still running"));
        }
    }

    @Test
    public void completesWithResult() {
        SkillExecutionHandle handle = new SkillExecutionHandle();
        handle.complete("ok");
        handle.await();
        assertEquals(SkillExecutionStatusEnum.COMPLETED, handle.status());
        assertEquals("ok", handle.result());
        assertEquals("ok", handle.resultOrThrow());
        assertTrue(handle.await(1L, TimeUnit.MILLISECONDS));
    }

    @Test
    public void marksCancelledAndThrowsCancellationError() {
        SkillExecutionHandle handle = new SkillExecutionHandle();
        handle.cancel();
        assertTrue(handle.isCancellationRequested());
        handle.cancelDone();
        assertEquals(SkillExecutionStatusEnum.CANCELLED, handle.status());
        try {
            handle.resultOrThrow();
            fail("expected ClaraException");
        } catch (ClaraException expected) {
            assertEquals("INVOCATION_CANCELLED", expected.getError().getCode());
        }
    }

    @Test
    public void recordsFailure() {
        SkillExecutionHandle handle = new SkillExecutionHandle();
        IllegalStateException failure = new IllegalStateException("boom");
        handle.fail(failure);
        assertEquals(SkillExecutionStatusEnum.FAILED, handle.status());
        try {
            handle.resultOrThrow();
            fail("expected failure");
        } catch (IllegalStateException expected) {
            assertSame(failure, expected);
        }
    }

    @Test
    public void failedWithoutCauseIsReported() {
        SkillExecutionHandle handle = new SkillExecutionHandle();
        handle.fail(null);
        try {
            handle.resultOrThrow();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("without a recorded cause"));
        }
    }

    @Test
    public void awaitRestoresInterruptFlag() {
        SkillExecutionHandle handle = new SkillExecutionHandle();
        Thread.currentThread().interrupt();
        try {
            handle.await();
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(handle.await(1L, TimeUnit.MILLISECONDS));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
