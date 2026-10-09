package com.clara.enums.workflow;

/**
 * 工作流实例状态。
 */
public enum WorkflowInstanceStatusEnum {
    /** 已创建。 */
    CREATED,
    /** 运行中。 */
    RUNNING,
    /** 等待人工输入。 */
    WAITING_INPUT,
    /** 已暂停。 */
    SUSPENDED,
    /** 成功完成。 */
    SUCCEEDED,
    /** 执行失败。 */
    FAILED,
    /** 已取消。 */
    CANCELLED;

    /**
     * 是否终态（成功 / 失败 / 取消）。
     *
     * @return 终态时返回 {@code true}
     */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
