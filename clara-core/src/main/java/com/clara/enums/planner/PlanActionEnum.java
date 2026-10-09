package com.clara.enums.planner;

/**
 * 计划可执行的动作。
 */
public enum PlanActionEnum {
    /** 调用。 */
    CALL,
    /** 等待。 */
    WAIT,
    /** 请求输入。 */
    REQUEST_INPUT,
    /** 重新规划。 */
    REPLAN,
    /** 完成。 */
    DONE,
    /** 暂停。 */
    SUSPEND,
    /** 失败。 */
    FAIL
}
