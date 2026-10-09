package com.clara.enums.planner;

/**
 * 执行记录状态。
 */
public enum ExecutionRecordStatusEnum {
    /** 已记录执行意图。 */
    INTENT_RECORDED,
    /** 运行中。 */
    RUNNING,
    /** 成功。 */
    SUCCEEDED,
    /** 失败。 */
    FAILED,
    /** 状态未知。 */
    UNKNOWN
}
