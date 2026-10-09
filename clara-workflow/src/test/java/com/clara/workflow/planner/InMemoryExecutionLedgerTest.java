package com.clara.workflow.planner;

import com.clara.dto.planner.BudgetAmount;
import com.clara.dto.planner.ExecutionRecord;
import com.clara.dto.planner.TaskExecutionResult;
import com.clara.enums.planner.ExecutionRecordStatusEnum;
import com.clara.exception.ClaraException;
import com.clara.exception.ErrorCodes;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link InMemoryExecutionLedger} 的单元测试：执行状态流转、幂等键查询与 UNKNOWN / FAILED 区分。
 */
public class InMemoryExecutionLedgerTest {

    private static TaskExecutionResult result() {
        return new TaskExecutionResult(Collections.<String, Object>singletonMap("out", "v"),
                BudgetAmount.ZERO);
    }

    @Test
    public void tracksExecutionLifecycle() {
        InMemoryExecutionLedger ledger = new InMemoryExecutionLedger();

        ExecutionRecord intent = ledger.recordIntent("run:T", "goal", 1, "T");
        assertEquals(ExecutionRecordStatusEnum.INTENT_RECORDED, intent.getStatus());

        ExecutionRecord running = ledger.beginAttempt("run:T");
        assertEquals(ExecutionRecordStatusEnum.RUNNING, running.getStatus());

        TaskExecutionResult result = result();
        ExecutionRecord succeeded = ledger.succeed("run:T", result);
        assertEquals(ExecutionRecordStatusEnum.SUCCEEDED, succeeded.getStatus());
        assertEquals(result, succeeded.getResult());

        ExecutionRecord found = ledger.find("run:T").get();
        assertEquals(ExecutionRecordStatusEnum.SUCCEEDED, found.getStatus());
    }

    @Test
    public void marksUnknownDistinctFromFailed() {
        InMemoryExecutionLedger ledger = new InMemoryExecutionLedger();
        ledger.recordIntent("run:T", "goal", 1, "T");
        ledger.beginAttempt("run:T");

        ExecutionRecord unknown = ledger.markUnknown("run:T", new RuntimeException("boom"));

        assertEquals(ExecutionRecordStatusEnum.UNKNOWN, unknown.getStatus());
        assertNotNull(unknown.getFailure());
        assertTrue(ledger.find("run:T").isPresent());
    }

    @Test
    public void recordsFailureAndAllowsRetry() {
        InMemoryExecutionLedger ledger = new InMemoryExecutionLedger();
        ledger.recordIntent("run:T", "goal", 1, "T");
        ledger.beginAttempt("run:T");

        ExecutionRecord failed = ledger.fail("run:T", new RuntimeException("boom"));
        assertEquals(ExecutionRecordStatusEnum.FAILED, failed.getStatus());

        ExecutionRecord retried = ledger.beginAttempt("run:T");
        assertEquals(ExecutionRecordStatusEnum.RUNNING, retried.getStatus());
    }

    @Test
    public void rejectsConflictingIdentity() {
        InMemoryExecutionLedger ledger = new InMemoryExecutionLedger();
        ledger.recordIntent("run:T", "goal", 1, "T");

        ClaraException error = assertThrows(ClaraException.class,
                () -> ledger.recordIntent("run:T", "goal", 1, "OTHER"));
        assertEquals(ErrorCodes.EXECUTION_LEDGER_CONFLICT, error.getError().getCode());
    }

    @Test
    public void rejectsInvalidTransitions() {
        InMemoryExecutionLedger ledger = new InMemoryExecutionLedger();
        ledger.recordIntent("run:T", "goal", 1, "T");

        ClaraException notRunning = assertThrows(ClaraException.class, () -> ledger.succeed("run:T", result()));
        assertEquals(ErrorCodes.EXECUTION_LEDGER_CONFLICT, notRunning.getError().getCode());

        ledger.beginAttempt("run:T");
        ledger.succeed("run:T", result());

        ClaraException afterSuccess = assertThrows(ClaraException.class, () -> ledger.beginAttempt("run:T"));
        assertEquals(ErrorCodes.EXECUTION_LEDGER_CONFLICT, afterSuccess.getError().getCode());
    }

    @Test
    public void rejectsBlankKey() {
        InMemoryExecutionLedger ledger = new InMemoryExecutionLedger();

        assertThrows(IllegalArgumentException.class, () -> ledger.find(" "));
    }
}
