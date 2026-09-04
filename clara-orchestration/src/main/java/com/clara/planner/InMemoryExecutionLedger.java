package com.clara.planner;

import com.clara.kernel.ErrorCategory;
import com.clara.kernel.ErrorCodes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class InMemoryExecutionLedger implements ExecutionLedger {
    private final Map<String, ExecutionRecord> records = new LinkedHashMap<String, ExecutionRecord>();
    public synchronized ExecutionRecord recordIntent(String key, String planId, int version, String taskId) {
        require(key); require(planId); require(taskId);
        ExecutionRecord current = records.get(key);
        if (current != null) {
            if (!current.getPlanId().equals(planId) || !current.getTaskId().equals(taskId)) conflict();
            return current;
        }
        ExecutionRecord created = new ExecutionRecord(key, planId, version, taskId,
                ExecutionRecordStatus.INTENT_RECORDED, 0, null, null);
        records.put(key, created); return created;
    }
    public synchronized ExecutionRecord beginAttempt(String key) {
        ExecutionRecord current = required(key);
        if (current.getStatus() != ExecutionRecordStatus.INTENT_RECORDED
                && current.getStatus() != ExecutionRecordStatus.FAILED) conflict();
        return replace(current, ExecutionRecordStatus.RUNNING, current.getAttempts() + 1, null, null);
    }
    public synchronized ExecutionRecord succeed(String key, TaskExecutionResult result) {
        if (result == null) throw new IllegalArgumentException("result must not be null");
        ExecutionRecord current = required(key);
        if (current.getStatus() == ExecutionRecordStatus.SUCCEEDED) return current;
        if (current.getStatus() != ExecutionRecordStatus.RUNNING) conflict();
        return replace(current, ExecutionRecordStatus.SUCCEEDED, current.getAttempts(), result, null);
    }
    public synchronized ExecutionRecord fail(String key, Throwable failure) {
        ExecutionRecord current = required(key);
        if (current.getStatus() != ExecutionRecordStatus.RUNNING) conflict();
        return replace(current, ExecutionRecordStatus.FAILED, current.getAttempts(), null, type(failure));
    }
    public synchronized ExecutionRecord markUnknown(String key, Throwable failure) {
        ExecutionRecord current = required(key);
        if (current.getStatus() == ExecutionRecordStatus.UNKNOWN) return current;
        if (current.getStatus() != ExecutionRecordStatus.RUNNING) conflict();
        return replace(current, ExecutionRecordStatus.UNKNOWN, current.getAttempts(), null, type(failure));
    }
    public synchronized Optional<ExecutionRecord> find(String key) {
        require(key); return Optional.ofNullable(records.get(key));
    }
    private ExecutionRecord required(String key) {
        require(key); ExecutionRecord value = records.get(key); if (value == null) conflict(); return value;
    }
    private ExecutionRecord replace(ExecutionRecord current, ExecutionRecordStatus status,
            int attempts, TaskExecutionResult result, String failure) {
        ExecutionRecord value = new ExecutionRecord(current.getIdempotencyKey(), current.getPlanId(),
                current.getPlanVersion(), current.getTaskId(), status, attempts, result, failure);
        records.put(current.getIdempotencyKey(), value); return value;
    }
    private static String type(Throwable value) { return value == null ? null : value.getClass().getName(); }
    private static void conflict() {
        throw PlannerErrors.error(ErrorCodes.EXECUTION_LEDGER_CONFLICT, ErrorCategory.CONFLICT);
    }
    private static void require(String value) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("ledger identity must not be blank");
    }
}
