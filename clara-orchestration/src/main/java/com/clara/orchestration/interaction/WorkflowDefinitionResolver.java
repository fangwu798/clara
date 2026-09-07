package com.clara.orchestration.interaction;

import com.clara.workflow.WorkflowDefinition;

/**
 * 解析并提供工作流定义。
 */
public interface WorkflowDefinitionResolver {
    WorkflowDefinition resolve(String instanceId);
}
