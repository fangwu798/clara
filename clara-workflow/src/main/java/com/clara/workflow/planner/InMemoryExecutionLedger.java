package com.clara.workflow.planner;

import com.clara.core.planner.IExecutionLedger;
import com.clara.dto.planner.ExecutionRecord;
import com.clara.dto.planner.TaskExecutionResult;
import com.clara.enums.planner.ExecutionRecordStatusEnum;
import com.clara.enums.ErrorCategoryEnum;
import com.clara.exception.ErrorCodes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 执行账本的内存实现，支撑幂等与重试判定。
 */
public final class InMemoryExecutionLedger implements IExecutionLedger {

    private final Map<String, ExecutionRecord> records =
            new LinkedHashMap<>();

    @Override
    public synchronized ExecutionRecord recordIntent(String idempotencyKey, String planId,
            int planVersion, String taskId) {
        require(idempotencyKey);
        require(planId);
        require(taskId);
        ExecutionRecord current = records.get(idempotencyKey);
        if (current != null) {
            if (!current.getPlanId().equals(planId) || !current.getTaskId().equals(taskId)) {
                conflict();
            }
            return current;
        }
        ExecutionRecord created = new ExecutionRecord(idempotencyKey, planId, planVersion, taskId,
                ExecutionRecordStatusEnum.INTENT_RECORDED, null, null);
        records.put(idempotencyKey, created);
        return created;
    }

    @Override
    public synchronized ExecutionRecord beginAttempt(String idempotencyKey) {
        ExecutionRecord current = required(idempotencyKey);
        if (current.getStatus() != ExecutionRecordStatusEnum.INTENT_RECORDED
                && current.getStatus() != ExecutionRecordStatusEnum.FAILED) {
            conflict();
        }
        return replace(current, ExecutionRecordStatusEnum.RUNNING, null, null);
    }

    @Override
    public synchronized ExecutionRecord succeed(String idempotencyKey, TaskExecutionResult result) {
        if (result == null) {
            throw new IllegalArgumentException("result must not be null");
        }
        ExecutionRecord current = required(idempotencyKey);
        if (current.getStatus() == ExecutionRecordStatusEnum.SUCCEEDED) {
            return current;
        }
        if (current.getStatus() != ExecutionRecordStatusEnum.RUNNING) {
            conflict();
        }
        return replace(current, ExecutionRecordStatusEnum.SUCCEEDED, result, null);
    }

    @Override
    public synchronized ExecutionRecord fail(String idempotencyKey, Throwable failure) {
        ExecutionRecord current = required(idempotencyKey);
        if (current.getStatus() != ExecutionRecordStatusEnum.RUNNING) {
            conflict();
        }
        return replace(current, ExecutionRecordStatusEnum.FAILED, null, failure);
    }

    @Override
    public synchronized ExecutionRecord markUnknown(String idempotencyKey, Throwable failure) {
        ExecutionRecord current = required(idempotencyKey);
        if (current.getStatus() == ExecutionRecordStatusEnum.UNKNOWN) {
            return current;
        }
        if (current.getStatus() != ExecutionRecordStatusEnum.RUNNING) {
            conflict();
        }
        return replace(current, ExecutionRecordStatusEnum.UNKNOWN, null, failure);
    }

    @Override
    public synchronized Optional<ExecutionRecord> find(String idempotencyKey) {
        require(idempotencyKey);
        return Optional.ofNullable(records.get(idempotencyKey));
    }

    private ExecutionRecord required(String idempotencyKey) {
        require(idempotencyKey);
        ExecutionRecord value = records.get(idempotencyKey);
        if (value == null) {
            conflict();
        }
        return value;
    }

    private ExecutionRecord replace(ExecutionRecord current, ExecutionRecordStatusEnum status,
            TaskExecutionResult result, Throwable failure) {
        ExecutionRecord value = new ExecutionRecord(current.getIdempotencyKey(), current.getPlanId(),
                current.getPlanVersion(), current.getTaskId(), status, result, failure);
        records.put(current.getIdempotencyKey(), value);
        return value;
    }

    private static void conflict() {
        throw PlannerErrors.error(ErrorCodes.EXECUTION_LEDGER_CONFLICT, ErrorCategoryEnum.CONFLICT);
    }

    private static void require(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("ledger identity must not be blank");
        }
    }
}
