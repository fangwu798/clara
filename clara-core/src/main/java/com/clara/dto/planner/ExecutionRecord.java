package com.clara.dto.planner;

import com.clara.enums.planner.ExecutionRecordStatusEnum;
import lombok.Data;

/**
 * 执行记录：任务副作用的持久化记录（幂等键、归属、状态与结果）。
 *
 * <p>归属由计划标识、计划版本与任务标识共同确定；{@code result} 与 {@code failure} 视状态而定：成功时携带
 * {@code result}，失败 / 未知时携带 {@code failure}。
 */
@Data
public final class ExecutionRecord {

    private final String idempotencyKey;
    private final String planId;
    private final int planVersion;
    private final String taskId;
    private final ExecutionRecordStatusEnum status;
    private final TaskExecutionResult result;
    private final Throwable failure;

    /**
     * 创建执行记录。
     *
     * @param idempotencyKey 幂等键，不可为空白
     * @param planId         计划标识，可为 null（尚未记录意图时）
     * @param planVersion    计划版本，不可为负
     * @param taskId         任务标识，可为 null（尚未记录意图时）
     * @param status         记录状态，不可为 null
     * @param result         任务执行结果，可为 null
     * @param failure        执行失败 / 未知时的异常，可为 null
     * @throws IllegalArgumentException 幂等键为空白或状态为 null 时
     */
    public ExecutionRecord(String idempotencyKey, String planId, int planVersion, String taskId,
            ExecutionRecordStatusEnum status, TaskExecutionResult result, Throwable failure) {
        if (blank(idempotencyKey) || status == null || planVersion < 0) {
            throw new IllegalArgumentException("execution record is invalid");
        }
        this.idempotencyKey = idempotencyKey;
        this.planId = planId;
        this.planVersion = planVersion;
        this.taskId = taskId;
        this.status = status;
        this.result = result;
        this.failure = failure;
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
