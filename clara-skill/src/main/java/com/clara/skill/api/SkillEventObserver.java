package com.clara.skill.api;

public interface SkillEventObserver {
    void onStarted(String skillId,String version);
    void onCompleted(SkillExecutionResult result);
    void onError(Throwable failure);
}
