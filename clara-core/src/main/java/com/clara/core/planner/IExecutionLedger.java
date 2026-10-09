package com.clara.core.planner;

import com.clara.dto.planner.ExecutionRecord;
import com.clara.dto.planner.TaskExecutionResult;

import java.util.Optional;

/**
 * 执行账本接口：跟踪任务执行的记录与状态。
 */
public interface IExecutionLedger {

    /**
     * 记录执行意图。
     */
    ExecutionRecord recordIntent(String idempotencyKey, String planId, int planVersion, String taskId);

    /**
     * 开始一次尝试。
     */
    ExecutionRecord beginAttempt(String idempotencyKey);

    /**
     * 标记成功。
     */
    ExecutionRecord succeed(String idempotencyKey, TaskExecutionResult result);

    /**
     * 标记失败。
     */
    ExecutionRecord fail(String idempotencyKey, Throwable failure);

    /**
     * 标记状态未知。
     */
    ExecutionRecord markUnknown(String idempotencyKey, Throwable failure);

    /**
     * 按幂等键查找执行记录。
     */
    Optional<ExecutionRecord> find(String idempotencyKey);
}
