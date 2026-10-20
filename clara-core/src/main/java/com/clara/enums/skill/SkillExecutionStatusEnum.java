package com.clara.enums.skill;

/**
 * 技能执行状态机：RUNNING → COMPLETED / CANCELLED / FAILED。
 */
public enum SkillExecutionStatusEnum {

    /** 执行中。 */
    RUNNING,
    /** 执行成功，结果可读。 */
    COMPLETED,
    /** 执行已取消。 */
    CANCELLED,
    /** 执行失败，异常原因可读。 */
    FAILED
}