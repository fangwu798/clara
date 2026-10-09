package com.clara.enums.planner;

/**
 * 任务节点状态。
 */
public enum TaskNodeStatusEnum {
    /** 待调度。 */
    PENDING,
    /** 就绪。 */
    READY,
    /** 运行中。 */
    RUNNING,
    /** 成功。 */
    SUCCEEDED,
    /** 失败。 */
    FAILED,
    /** 跳过。 */
    SKIPPED,
    /** 已取消。 */
    CANCELLED,
    /** 因能力变更被阻断。 */
    BLOCKED_CAPABILITY_CHANGED
}
