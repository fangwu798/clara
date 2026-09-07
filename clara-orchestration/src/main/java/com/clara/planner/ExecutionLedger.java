package com.clara.planner;

import java.util.Optional;

/**
 * 跟踪执行的预留与使用情况。
 */
public interface ExecutionLedger {
    ExecutionRecord recordIntent(String idempotencyKey, String planId, int planVersion, String taskId);
    ExecutionRecord beginAttempt(String idempotencyKey);
    ExecutionRecord succeed(String idempotencyKey, TaskExecutionResult result);
    ExecutionRecord fail(String idempotencyKey, Throwable failure);
    ExecutionRecord markUnknown(String idempotencyKey, Throwable failure);
    Optional<ExecutionRecord> find(String idempotencyKey);
}
