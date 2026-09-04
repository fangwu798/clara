package com.clara.orchestration.interaction;

import com.clara.workflow.WorkflowDefinition;
import com.clara.workflow.WorkflowEngine;
import com.clara.workflow.WorkflowResult;
import java.util.List;
import java.util.Map;

public final class WorkflowInteractionResumeCoordinator implements InteractionResumeCoordinator {
    private final WorkflowEngine engine;
    private final WorkflowDefinitionResolver definitions;
    public WorkflowInteractionResumeCoordinator(WorkflowEngine engine,
            WorkflowDefinitionResolver definitions) {
        if (engine == null || definitions == null) throw new IllegalArgumentException("engine and definitions are required");
        this.engine = engine; this.definitions = definitions;
    }
    public ResumeOutcome resumeAndRecord(InteractionRequest trigger,
            List<InteractionResponse> responses, Map<String, Object> scopeUpdates) {
        WorkflowDefinition definition = definitions.resolve(trigger.getInstanceId());
        if (definition == null) throw new IllegalArgumentException("workflow definition was not resolved");
        WorkflowResult result = engine.resume(definition, trigger.getInstanceId(), scopeUpdates,
                trigger.getExpectedInstanceVersion());
        return new ResumeOutcome(result.getStatus().name(), result.getCheckpointVersion());
    }
    public void interactionCancelled(InteractionRequest request, String reason) {
        engine.cancel(request.getInstanceId());
    }
}
