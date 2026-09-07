package com.clara.skill.api;

/**
 * 接收技能事件产生的事件。
 */
public interface SkillEventObserver {
    void onStarted(String skillId,String version);
    void onCompleted(SkillExecutionResult result);
    void onError(Throwable failure);
}
