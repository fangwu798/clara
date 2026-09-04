package com.clara.workspace.runtime;

import com.clara.planner.TaskPlan;
import com.clara.skill.api.SkillDefinition;
import com.clara.skill.api.SkillExecutionRequest;
import java.util.Map;

public interface GeneralWorkspacePlanFactory {
    TaskPlan create(SkillDefinition definition, SkillExecutionRequest request,
            Map<String, Object> feedback);
}
