package com.clara.orchestration.interaction;

import com.clara.workflow.WorkflowDefinition;

public interface WorkflowDefinitionResolver {
    WorkflowDefinition resolve(String instanceId);
}
