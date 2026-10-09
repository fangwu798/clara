package com.clara.enums.planner;

/**
 * 计划状态。
 */
public enum PlanStatusEnum {
    /** 草稿。 */
    DRAFT,
    /** 已校验。 */
    VALIDATED,
    /** 运行中。 */
    RUNNING,
    /** 等待人工输入。 */
    WAITING_INPUT,
    /** 重新规划中。 */
    REPLANNING,
    /** 成功。 */
    SUCCEEDED,
    /** 失败。 */
    FAILED,
    /** 已取消。 */
    CANCELLED
}
