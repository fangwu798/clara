package com.clara.planner;

/**
 * 表示执行的持久化记录。
 */
public final class ExecutionRecord {
    private final String idempotencyKey;
    private final String planId;
    private final int planVersion;
    private final String taskId;
    private final ExecutionRecordStatus status;
    private final int attempts;
    private final TaskExecutionResult result;
    private final String failureType;
    ExecutionRecord(String key, String planId, int planVersion, String taskId,
            ExecutionRecordStatus status, int attempts, TaskExecutionResult result, String failureType) {
        this.idempotencyKey = key; this.planId = planId; this.planVersion = planVersion;
        this.taskId = taskId; this.status = status; this.attempts = attempts;
        this.result = result; this.failureType = failureType;
    }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getPlanId() { return planId; }
    public int getPlanVersion() { return planVersion; }
    public String getTaskId() { return taskId; }
    public ExecutionRecordStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public TaskExecutionResult getResult() { return result; }
    public String getFailureType() { return failureType; }
}
