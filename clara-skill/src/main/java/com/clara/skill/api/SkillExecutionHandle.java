package com.clara.skill.api;

/**
 * 控制并观察正在执行的技能执行。
 */
public interface SkillExecutionHandle {
    boolean cancel();
    boolean isDone();
}
