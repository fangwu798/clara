package com.clara.workspace.runtime;

import com.clara.planner.TaskPlan;
import com.clara.skill.api.SkillDefinition;
import com.clara.skill.api.SkillExecutionRequest;

import java.util.Map;

/**
 * 负责创建通用工作区计划实例。
 */
public interface GeneralWorkspacePlanFactory {
    TaskPlan create(SkillDefinition definition, SkillExecutionRequest request,
                    Map<String, Object> feedback);
}
