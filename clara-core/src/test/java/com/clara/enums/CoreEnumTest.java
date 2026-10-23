package com.clara.enums;

import com.clara.enums.interaction.CancelConfirmationDecisionEnum;
import com.clara.enums.interaction.HistoryClearScopeEnum;
import com.clara.enums.interaction.SessionCommandOutcomeEnum;
import com.clara.enums.skill.SkillExecutionStatusEnum;
import com.clara.enums.workflow.WorkflowNodeTypeEnum;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 内核枚举契约：常量集合稳定、名称唯一、可按名称回读。
 *
 * <p>这些枚举既参与配置解析（如 {@code provider}、{@code protocol}）又参与控制台交互，
 * 常量被重命名或重复会直接影响对外契约，因此用回读测试固定。
 */
public class CoreEnumTest {

    @Test
    public void providerNamesAreStableAndRoundTrip() {
        assertEquals(6, ModelProviderEnum.values().length);
        assertEquals(ModelProviderEnum.OPENAI, ModelProviderEnum.valueOf("OPENAI"));
        assertEquals(ModelProviderEnum.VOLCENGINE, ModelProviderEnum.valueOf("VOLCENGINE"));
        assertEquals(ModelProviderEnum.DEEPSEEK, ModelProviderEnum.valueOf("DEEPSEEK"));
        assertEquals(ModelProviderEnum.ALIBABA, ModelProviderEnum.valueOf("ALIBABA"));
        assertEquals(ModelProviderEnum.KIMI, ModelProviderEnum.valueOf("KIMI"));
        assertEquals(ModelProviderEnum.ANTHROPIC, ModelProviderEnum.valueOf("ANTHROPIC"));
        assertUnique(ModelProviderEnum.values());
    }

    @Test
    public void finishReasonsAreStableAndRoundTrip() {
        assertEquals(6, FinishReasonEnum.values().length);
        assertEquals(FinishReasonEnum.STOP, FinishReasonEnum.valueOf("STOP"));
        assertEquals(FinishReasonEnum.LENGTH, FinishReasonEnum.valueOf("LENGTH"));
        assertEquals(FinishReasonEnum.TOOL_CALLS, FinishReasonEnum.valueOf("TOOL_CALLS"));
        assertEquals(FinishReasonEnum.CONTENT_FILTER, FinishReasonEnum.valueOf("CONTENT_FILTER"));
        assertEquals(FinishReasonEnum.CANCELLED, FinishReasonEnum.valueOf("CANCELLED"));
        assertEquals(FinishReasonEnum.ERROR, FinishReasonEnum.valueOf("ERROR"));
        assertUnique(FinishReasonEnum.values());
    }

    @Test
    public void capabilityAndProtocolNamesAreStable() {
        assertEquals(5, CapabilityKindEnum.values().length);
        assertEquals(CapabilityKindEnum.CHAT, CapabilityKindEnum.valueOf("CHAT"));
        assertEquals(CapabilityKindEnum.IMAGE, CapabilityKindEnum.valueOf("IMAGE"));
        assertEquals(CapabilityKindEnum.VIDEO, CapabilityKindEnum.valueOf("VIDEO"));
        assertEquals(CapabilityKindEnum.AUDIO_TRANSCRIPTION, CapabilityKindEnum.valueOf("AUDIO_TRANSCRIPTION"));
        assertEquals(CapabilityKindEnum.SPEECH, CapabilityKindEnum.valueOf("SPEECH"));

        assertEquals(3, ProtocolEnum.values().length);
        assertEquals(ProtocolEnum.COMPLETION, ProtocolEnum.valueOf("COMPLETION"));
        assertEquals(ProtocolEnum.RESPONSE, ProtocolEnum.valueOf("RESPONSE"));
        assertEquals(ProtocolEnum.MESSAGES, ProtocolEnum.valueOf("MESSAGES"));
    }

    @Test
    public void availabilityAndSkillStatusNamesAreStable() {
        assertEquals(4, AvailabilityLevelEnum.values().length);
        assertEquals(AvailabilityLevelEnum.INFO, AvailabilityLevelEnum.valueOf("INFO"));
        assertEquals(AvailabilityLevelEnum.OK, AvailabilityLevelEnum.valueOf("OK"));
        assertEquals(AvailabilityLevelEnum.WARN, AvailabilityLevelEnum.valueOf("WARN"));
        assertEquals(AvailabilityLevelEnum.FAIL, AvailabilityLevelEnum.valueOf("FAIL"));

        assertEquals(4, SkillExecutionStatusEnum.values().length);
        assertEquals(SkillExecutionStatusEnum.RUNNING, SkillExecutionStatusEnum.valueOf("RUNNING"));
        assertEquals(SkillExecutionStatusEnum.COMPLETED, SkillExecutionStatusEnum.valueOf("COMPLETED"));
        assertEquals(SkillExecutionStatusEnum.CANCELLED, SkillExecutionStatusEnum.valueOf("CANCELLED"));
        assertEquals(SkillExecutionStatusEnum.FAILED, SkillExecutionStatusEnum.valueOf("FAILED"));
    }

    @Test
    public void workflowNodeTypesAreStable() {
        assertEquals(8, WorkflowNodeTypeEnum.values().length);
        assertEquals(WorkflowNodeTypeEnum.AGENT, WorkflowNodeTypeEnum.valueOf("AGENT"));
        assertEquals(WorkflowNodeTypeEnum.TOOL, WorkflowNodeTypeEnum.valueOf("TOOL"));
        assertEquals(WorkflowNodeTypeEnum.SEQUENCE, WorkflowNodeTypeEnum.valueOf("SEQUENCE"));
        assertEquals(WorkflowNodeTypeEnum.PARALLEL, WorkflowNodeTypeEnum.valueOf("PARALLEL"));
        assertEquals(WorkflowNodeTypeEnum.CONDITION, WorkflowNodeTypeEnum.valueOf("CONDITION"));
        assertEquals(WorkflowNodeTypeEnum.LOOP, WorkflowNodeTypeEnum.valueOf("LOOP"));
        assertEquals(WorkflowNodeTypeEnum.HUMAN, WorkflowNodeTypeEnum.valueOf("HUMAN"));
        assertEquals(WorkflowNodeTypeEnum.REVIEW, WorkflowNodeTypeEnum.valueOf("REVIEW"));
        assertUnique(WorkflowNodeTypeEnum.values());
    }

    @Test
    public void interactionEnumNamesAreStable() {
        assertEquals(4, SessionCommandOutcomeEnum.values().length);
        assertEquals(SessionCommandOutcomeEnum.EXIT, SessionCommandOutcomeEnum.valueOf("EXIT"));
        assertEquals(SessionCommandOutcomeEnum.REINIT, SessionCommandOutcomeEnum.valueOf("REINIT"));
        assertEquals(SessionCommandOutcomeEnum.HANDLED, SessionCommandOutcomeEnum.valueOf("HANDLED"));
        assertEquals(SessionCommandOutcomeEnum.INPUT, SessionCommandOutcomeEnum.valueOf("INPUT"));

        assertEquals(3, HistoryClearScopeEnum.values().length);
        assertEquals(HistoryClearScopeEnum.ALL, HistoryClearScopeEnum.valueOf("ALL"));
        assertEquals(HistoryClearScopeEnum.FIRST, HistoryClearScopeEnum.valueOf("FIRST"));
        assertEquals(HistoryClearScopeEnum.LAST, HistoryClearScopeEnum.valueOf("LAST"));

        assertEquals(3, CancelConfirmationDecisionEnum.values().length);
        assertEquals(CancelConfirmationDecisionEnum.IGNORED, CancelConfirmationDecisionEnum.valueOf("IGNORED"));
        assertEquals(CancelConfirmationDecisionEnum.PROMPT, CancelConfirmationDecisionEnum.valueOf("PROMPT"));
        assertEquals(CancelConfirmationDecisionEnum.CANCEL, CancelConfirmationDecisionEnum.valueOf("CANCEL"));
    }

    private static <E extends Enum<E>> void assertUnique(E[] values) {
        Set<String> names = new HashSet<>();
        for (E value : values) {
            assertTrue("duplicate enum name: " + value.name(), names.add(value.name()));
        }
    }
}