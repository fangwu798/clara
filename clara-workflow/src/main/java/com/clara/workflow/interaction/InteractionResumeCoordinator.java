package com.clara.workflow.interaction;

import com.clara.core.interaction.IInteractionResumeCoordinator;
import com.clara.core.interaction.IWorkflowDefinitionResolver;
import com.clara.core.workflow.IWorkflowEngine;
import com.clara.dto.interaction.InteractionRequest;
import com.clara.dto.interaction.InteractionResponse;
import com.clara.dto.interaction.ResumeOutcome;
import com.clara.dto.workflow.WorkflowDefinition;
import com.clara.dto.workflow.WorkflowResult;

import java.util.List;
import java.util.Map;

/**
 * 协调交互恢复与取消的通用流程。
 */
public final class InteractionResumeCoordinator implements IInteractionResumeCoordinator {

    private final IWorkflowEngine engine;
    private final IWorkflowDefinitionResolver definitions;

    public InteractionResumeCoordinator(IWorkflowEngine engine, IWorkflowDefinitionResolver definitions) {
        if (engine == null || definitions == null) {
            throw new IllegalArgumentException("engine and definitions are required");
        }
        this.engine = engine;
        this.definitions = definitions;
    }

    @Override
    public ResumeOutcome resumeAndRecord(InteractionRequest trigger, List<InteractionResponse> responses, Map<String, Object> scopeUpdates) {
        WorkflowDefinition definition = definitions.resolve(trigger.getContext().getInstanceId());
        if (definition == null) {
            throw new IllegalArgumentException("workflow definition was not resolved");
        }
        WorkflowResult result = engine.resume(definition, trigger.getContext().getInstanceId(), scopeUpdates, trigger.getContext().getExpectedInstanceVersion());
        return new ResumeOutcome(result.getStatus().name(), result.getCheckpointVersion());
    }

    @Override
    public void interactionCancelled(InteractionRequest request, String reason) {
        engine.cancel(request.getContext().getInstanceId());
    }
}
