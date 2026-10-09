package com.clara.dto.interaction;

import lombok.Data;

/**
 * 反馈结果：反馈操作的执行结果（请求、是否幂等重放与恢复结果）。
 */
@Data
public final class FeedbackResult {

    private final InteractionRequest request;
    private final boolean idempotentReplay;
    private final ResumeOutcome resumeOutcome;

    /**
     * 创建反馈结果。
     *
     * @param request         对应的交互请求，不可为 null
     * @param idempotentReplay 是否为幂等重放
     * @param resumeOutcome   恢复处理结果，可为 null（未触发恢复时）
     * @throws IllegalArgumentException request 为 null 时
     */
    public FeedbackResult(InteractionRequest request, boolean idempotentReplay, ResumeOutcome resumeOutcome) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        this.request = request;
        this.idempotentReplay = idempotentReplay;
        this.resumeOutcome = resumeOutcome;
    }

    /**
     * 是否已触发实例恢复。
     *
     * @return 恢复结果非 null 时返回 {@code true}
     */
    public boolean isResumed() {
        return resumeOutcome != null;
    }
}
