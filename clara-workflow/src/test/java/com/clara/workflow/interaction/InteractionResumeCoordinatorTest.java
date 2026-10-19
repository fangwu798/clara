package com.clara.workflow.interaction;

import com.clara.core.interaction.IWorkflowDefinitionResolver;
import com.clara.core.workflow.IWorkflowEngine;
import com.clara.dto.interaction.InteractionContext;
import com.clara.dto.interaction.InteractionRequest;
import com.clara.dto.interaction.InteractionResponse;
import com.clara.dto.interaction.Question;
import com.clara.dto.interaction.ResumeOutcome;
import com.clara.dto.workflow.ExecutionScope;
import com.clara.dto.workflow.WorkflowDefinition;
import com.clara.dto.workflow.WorkflowResult;
import com.clara.enums.interaction.InteractionStateEnum;
import com.clara.enums.interaction.InteractionTypeEnum;
import com.clara.enums.workflow.WorkflowInstanceStatusEnum;
import com.clara.workflow.node.WorkflowNodes;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * {@link InteractionResumeCoordinator} 的单元测试：恢复并记录结果、取消标记。
 */
public class InteractionResumeCoordinatorTest {

    private static final class FakeEngine implements IWorkflowEngine {
        private String resumedInstanceId;
        private long expectedVersion = -1L;
        private Map<String, Object> resumedInputs;
        private String cancelledInstanceId;

        @Override
        public WorkflowResult start(WorkflowDefinition definition, String instanceId,
                Map<String, Object> inputs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WorkflowResult resume(WorkflowDefinition definition, String instanceId,
                Map<String, Object> inputs) {
            return resume(definition, instanceId, inputs, -1L);
        }

        @Override
        public WorkflowResult resume(WorkflowDefinition definition, String instanceId,
                Map<String, Object> inputs, long expectedCheckpointVersion) {
            this.resumedInstanceId = instanceId;
            this.expectedVersion = expectedCheckpointVersion;
            this.resumedInputs = inputs;
            return new WorkflowResult(instanceId, WorkflowInstanceStatusEnum.SUCCEEDED,
                    new ExecutionScope(inputs), 7L);
        }

        @Override
        public WorkflowResult cancel(String instanceId) {
            this.cancelledInstanceId = instanceId;
            return new WorkflowResult(instanceId, WorkflowInstanceStatusEnum.CANCELLED,
                    new ExecutionScope(Collections.<String, Object>emptyMap()), 1L);
        }

        @Override
        public WorkflowResult suspend(String instanceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }
    }

    private static Question question() {
        return Question.builder(InteractionTypeEnum.TEXT_INPUT, "Q")
                .required(true).scopeKey("answer").maxTextLength(50).build();
    }

    private static InteractionRequest trigger(String instanceId, long expectedVersion) {
        InteractionContext context = new InteractionContext("i1", "r1", instanceId, "node-1",
                expectedVersion, 100L, 9999L);
        return new InteractionRequest(context, question(), InteractionStateEnum.PENDING, null, null);
    }

    private static WorkflowDefinition definition() {
        return new WorkflowDefinition("wf", "1", null, null, WorkflowNodes.human("h", "answer"));
    }

    @Test
    public void resumesAndRecordsOutcome() {
        FakeEngine engine = new FakeEngine();
        IWorkflowDefinitionResolver resolver = instanceId -> definition();
        InteractionResumeCoordinator coordinator = new InteractionResumeCoordinator(engine, resolver);

        ResumeOutcome outcome = coordinator.resumeAndRecord(trigger("inst-1", 3L),
                Collections.<InteractionResponse>emptyList(),
                Collections.<String, Object>singletonMap("answer", "yes"));

        assertEquals("SUCCEEDED", outcome.getInstanceStatus());
        assertEquals(7L, outcome.getInstanceVersion());
        assertEquals("inst-1", engine.resumedInstanceId);
        assertEquals(3L, engine.expectedVersion);
        assertEquals("yes", engine.resumedInputs.get("answer"));
    }

    @Test
    public void cancelsUnderlyingInstance() {
        FakeEngine engine = new FakeEngine();
        IWorkflowDefinitionResolver resolver = instanceId -> definition();
        InteractionResumeCoordinator coordinator = new InteractionResumeCoordinator(engine, resolver);

        coordinator.interactionCancelled(trigger("inst-9", 0L), "changed");

        assertEquals("inst-9", engine.cancelledInstanceId);
    }
}
