package com.clara.dto.interaction;

import com.clara.enums.StreamEventEnum;
import com.clara.enums.interaction.InteractionStateEnum;
import com.clara.enums.interaction.InteractionTypeEnum;
import com.clara.dto.model.StreamEvent;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 交互 DTO：问题校验、请求/响应装配与流事件字段。
 */
public class InteractionDtoTest {

    /**
     * 文本问题：必填项生效，默认答案只读。
     */
    @Test
    public void buildsQuestion() {
        Map<String, Object> defaultAnswer = new HashMap<>();
        defaultAnswer.put("value", "yes");
        InteractionOption option = new InteractionOption("yes", "是");
        assertEquals("yes", option.getOptionId());
        assertEquals("是", option.getLabel());

        Question question = Question.builder(InteractionTypeEnum.SINGLE_SELECT, "continue?")
                .options(Collections.singletonList(option))
                .answerSchema("{}")
                .defaultAnswer(defaultAnswer)
                .required(true)
                .scopeKey("scope")
                .maxTextLength(100)
                .build();
        assertEquals(InteractionTypeEnum.SINGLE_SELECT, question.getType());
        assertEquals("continue?", question.getText());
        assertEquals(1, question.getOptions().size());
        assertEquals("{}", question.getAnswerSchema());
        assertEquals("yes", question.getDefaultAnswer().get("value"));
        assertTrue(question.isRequired());
        assertEquals("scope", question.getScopeKey());
        assertEquals(100, question.getMaxTextLength());
        assertThrows(UnsupportedOperationException.class, () -> question.getOptions().add(option));
        assertThrows(UnsupportedOperationException.class, () -> question.getDefaultAnswer().put("x", 1));

        assertThrows(IllegalArgumentException.class, () -> Question.builder(null, "q").build());
        assertThrows(IllegalArgumentException.class, () -> Question.builder(InteractionTypeEnum.TEXT_INPUT, " ").build());
        assertThrows(IllegalArgumentException.class, () -> Question.builder(InteractionTypeEnum.TEXT_INPUT, "q")
                .maxTextLength(10).build());
        assertThrows(IllegalArgumentException.class, () -> Question.builder(InteractionTypeEnum.TEXT_INPUT, "q")
                .scopeKey("s").maxTextLength(0).build());
        assertThrows(IllegalArgumentException.class, () -> Question.builder(InteractionTypeEnum.SINGLE_SELECT, "q")
                .scopeKey("s").maxTextLength(10).build());
        assertThrows(IllegalArgumentException.class, () -> Question.builder(InteractionTypeEnum.SINGLE_SELECT, "q")
                .scopeKey("s").maxTextLength(10)
                .options(Arrays.asList(option, new InteractionOption("yes", "重复"))).build());
        assertTrue(Question.builder(InteractionTypeEnum.TEXT_INPUT, "q").scopeKey("s").maxTextLength(10)
                .build().getOptions().isEmpty());
    }

    /**
     * 交互上下文、请求、响应与反馈的字段与校验。
     */
    @Test
    public void buildsInteractionFlow() {
        InteractionContext context = new InteractionContext("i1", "run1", "inst1", "node1", 2L, 100L, 200L);
        assertEquals("i1", context.getInteractionId());
        assertEquals("run1", context.getRunId());
        assertEquals("inst1", context.getInstanceId());
        assertEquals("node1", context.getNodeOrTaskId());
        assertEquals(2L, context.getExpectedInstanceVersion());
        assertEquals(100L, context.getCreatedAtEpochMillis());
        assertEquals(200L, context.getExpiresAtEpochMillis());

        assertThrows(IllegalArgumentException.class,
                () -> new InteractionContext(" ", "r", "i", "n", 0, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new InteractionContext("i", "r", "i", "n", -1, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new InteractionContext("i", "r", "i", "n", 0, 10, 10));

        Question question = Question.builder(InteractionTypeEnum.CONFIRMATION, "ok?")
                .scopeKey("s").maxTextLength(10).build();
        Map<String, Object> answer = new HashMap<>();
        answer.put("value", "yes");
        InteractionResponse response = new InteractionResponse("i1", answer, "user", "idem", 300L);
        assertEquals("i1", response.getInteractionId());
        assertEquals("yes", response.getAnswer().get("value"));
        assertEquals("user", response.getResponderId());
        assertEquals("idem", response.getIdempotencyKey());
        assertEquals(300L, response.getSubmittedAtEpochMillis());

        InteractionRequest request = new InteractionRequest(context, question,
                InteractionStateEnum.ANSWERED, response, null);
        assertEquals(context, request.getContext());
        assertEquals(question, request.getQuestion());
        assertEquals(InteractionStateEnum.ANSWERED, request.getState());
        assertEquals(response, request.getResponse());
        assertNull(request.getTerminalReason());

        assertThrows(IllegalArgumentException.class,
                () -> new InteractionRequest(null, question, InteractionStateEnum.PENDING, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new InteractionRequest(context, null, InteractionStateEnum.PENDING, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new InteractionRequest(context, question, null, null, null));

        FeedbackResult feedback = new FeedbackResult(request, true, new ResumeOutcome("RUNNING", 3L));
        assertTrue(feedback.isResumed());
        assertTrue(feedback.isIdempotentReplay());
        assertEquals("RUNNING", feedback.getResumeOutcome().getInstanceStatus());
        assertEquals(3L, feedback.getResumeOutcome().getInstanceVersion());
        assertFalse(new FeedbackResult(request, false, null).isResumed());
        assertThrows(IllegalArgumentException.class, () -> new FeedbackResult(null, false, null));

        InteractionFeedback input = new InteractionFeedback("run1", "i1", "user", 2L, "idem", answer);
        assertEquals("run1", input.getRunId());
        assertEquals("i1", input.getInteractionId());
        assertEquals("user", input.getResponderId());
        assertEquals(2L, input.getExpectedInstanceVersion());
        assertEquals("idem", input.getIdempotencyKey());
        assertEquals("yes", input.getAnswer().get("value"));
    }

    /**
     * 流事件：类型与序号必填，其余字段按事件类型携带。
     */
    @Test
    public void buildsStreamEvent() {
        StreamEvent delta = new StreamEvent(StreamEventEnum.TEXT_DELTA, 1L, "hi");
        assertEquals(StreamEventEnum.TEXT_DELTA, delta.getType());
        assertEquals(1L, delta.getSequence());
        assertEquals("hi", delta.getPayload());
        assertNull(delta.getToolCallId());
        assertNull(delta.getToolName());
        assertNull(delta.getResponse());
        assertNull(delta.getError());

        StreamEvent tool = new StreamEvent(StreamEventEnum.TOOL_CALL_COMPLETE, 2L, "{}",
                "call_1", "search", null, null);
        assertEquals("call_1", tool.getToolCallId());
        assertEquals("search", tool.getToolName());

        assertThrows(IllegalArgumentException.class, () -> new StreamEvent(null, 0L, "x"));
        assertThrows(IllegalArgumentException.class, () -> new StreamEvent(StreamEventEnum.LOG, -1L, "x"));
    }
}