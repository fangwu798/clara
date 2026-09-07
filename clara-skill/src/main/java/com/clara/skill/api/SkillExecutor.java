package com.clara.skill.api;

/**
 * 定义。
 */
public interface SkillExecutor {
    SkillExecutionResult execute(SkillExecutionRequest request);
    SkillExecutionHandle executeStream(SkillExecutionRequest request,SkillEventObserver observer);
    SkillExecutionResult resume(SkillResumeRequest request);
}
