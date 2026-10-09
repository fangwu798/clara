package com.clara.dto.interaction;

import lombok.Data;

/**
 * 恢复结果：恢复的处理结果（实例状态 + 版本）。
 */
@Data
public final class ResumeOutcome {

    private final String instanceStatus;
    private final long instanceVersion;

    /**
     * 创建恢复结果。
     *
     * @param instanceStatus  恢复后的实例状态，不可为空白
     * @param instanceVersion 恢复后的实例版本，不可为负
     * @throws IllegalArgumentException 任一字段不合法时
     */
    public ResumeOutcome(String instanceStatus, long instanceVersion) {
        if (instanceStatus == null || instanceStatus.trim().isEmpty() || instanceVersion < 0L) {
            throw new IllegalArgumentException("resume outcome is invalid");
        }
        this.instanceStatus = instanceStatus;
        this.instanceVersion = instanceVersion;
    }
}
