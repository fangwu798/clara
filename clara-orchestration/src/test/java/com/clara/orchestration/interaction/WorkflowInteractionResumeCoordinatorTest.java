package com.clara.orchestration.interaction;

import com.clara.workflow.InMemoryCheckpointStore;
import com.clara.workflow.WorkflowDefinition;
import com.clara.workflow.WorkflowEngine;
import com.clara.workflow.WorkflowInstanceStatus;
import com.clara.workflow.WorkflowNodes;
import com.clara.workflow.WorkflowResult;
import java.util.Collections;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class WorkflowInteractionResumeCoordinatorTest {
    private WorkflowEngine engine;

    @After public void tearDown() { if (engine != null) engine.close(); }

    @Test
    public void writesAnswerToScopeAndResumesExactCheckpointVersion() {
        final WorkflowDefinition definition = new WorkflowDefinition("flow", "1", "{}", "{}",
                WorkflowNodes.human("approval", "approved"));
        engine = new WorkflowEngine(new InMemoryCheckpointStore(), 1);
        WorkflowResult waiting = engine.start(definition, "instance", null);
        assertEquals(WorkflowInstanceStatus.WAITING_INPUT, waiting.getStatus());
        WorkflowInteractionResumeCoordinator coordinator = new WorkflowInteractionResumeCoordinator(
                engine, new WorkflowDefinitionResolver() {
                    public WorkflowDefinition resolve(String instanceId) { return definition; }
                });
        InteractionRequest request = new InteractionRequest(new InteractionContext("interaction",
                "tenant", "run", "instance", "approval", waiting.getCheckpointVersion(), 1L, 100L),
                new Question(InteractionType.CONFIRMATION, "approve", null, "{}", null,
                        true, "approved", 100), InteractionState.PENDING, null, null);
        InteractionResponse response = new InteractionResponse("interaction",
                Collections.<String, Object>singletonMap("decision", "CONFIRM"),
                "user", "key", 2L);

        ResumeOutcome outcome = coordinator.resumeAndRecord(request,
                Collections.singletonList(response),
                Collections.<String, Object>singletonMap("approved", response.getAnswer()));
        assertEquals(WorkflowInstanceStatus.SUCCEEDED.name(), outcome.getInstanceStatus());
    }
}
