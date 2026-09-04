package com.clara.skill.runtime;

import com.clara.skill.api.SkillDefinition;
import com.clara.skill.api.SkillExecutionRequest;
import com.clara.skill.api.SkillExecutionResult;
import com.clara.skill.api.SkillResumeRequest;

public interface SkillHandler {
    SkillExecutionResult execute(SkillDefinition definition,SkillExecutionRequest request) throws Exception;
    SkillExecutionResult resume(SkillDefinition definition,SkillResumeRequest request) throws Exception;
}
