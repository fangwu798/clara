package com.clara.orchestration;

import com.clara.core.memory.ITokenCountEstimator;
import com.clara.core.memory.IWindowMemory;
import com.clara.core.message.IContent;
import com.clara.core.skill.IToolInvoker;
import com.clara.dto.memory.WindowPolicy;
import com.clara.enums.CapabilityKindEnum;
import com.clara.core.model.IRequest;
import com.clara.core.model.IResponse;
import com.clara.kernel.api.IStreamModel;
import com.clara.exception.ClaraException;
import com.clara.exception.ErrorCategory;
import com.clara.exception.ErrorCodes;
import com.clara.kernel.http.HttpResponse;
import com.clara.kernel.message.delta.AssistantContent;
import com.clara.kernel.message.delta.TokenUsage;
import com.clara.kernel.message.delta.ToolContent;
import com.clara.kernel.message.delta.UserContent;
import com.clara.kernel.stream.IStreamHandle;
import com.clara.kernel.stream.IStreamObserver;
import com.clara.orchestration.api.ConversationRequest;
import com.clara.orchestration.api.ConversationResult;
import com.clara.orchestration.api.OrchestrationStatusEnum;
import com.clara.orchestration.capability.CapabilityDispatcher;
import com.clara.orchestration.context.ContextAssembler;
import com.clara.orchestration.context.SemanticRecallCoordinator;
import com.clara.orchestration.context.WindowCompressionCoordinator;
import com.clara.orchestration.record.CallRecordPublisher;
import com.clara.orchestration.skill.SkillMatchCoordinator;
import com.clara.orchestration.tool.ToolRoundTripCoordinator;
import com.clara.core.tool.IToolInvocation;
import com.clara.dto.tool.ToolCall;
import com.clara.dto.tool.ToolCallContent;
import com.clara.dto.tool.ToolCallFunction;
import com.clara.dto.tool.ToolResult;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link DefaultConversationOrchestrator} 单元测试：单轮对话、工具往返、往返超限与入参校验。
 */
public class DefaultConversationOrchestratorTest {

    @Test
    public void constructorRejectsNullDependencies() {
        ContextAssembler assembler = nullDepAssembler();
        assertThrows(IllegalArgumentException.class, () -> new DefaultConversationOrchestrator(
                null, assembler, new CapabilityDispatcher(new ScriptedModel()),
                new ToolRoundTripCoordinator(new RecordingInvoker()), new CallRecordPublisher(null), null));
    }

    @Test
    public void converseWithoutToolsCompletesAndAppendsAssistantMessage() {
        FakeWindowMemory window = new FakeWindowMemory("c1");
        ScriptedModel model = new ScriptedModel();
        model.script(contentResponse("hello", 10, 5, 15, 3));
        RecordingInvoker invoker = new RecordingInvoker();
        DefaultConversationOrchestrator orchestrator = orchestrator(window, model, invoker);

        ConversationResult result = orchestrator.converse(request("c1", "m1", "hi"));

        assertEquals(OrchestrationStatusEnum.COMPLETED, result.getStatus());
        assertEquals("hello", result.getContent());
        assertEquals(0, result.getToolRoundTrips());
        assertEquals("c1", result.getConversationId());

        TokenUsage usage = result.getUsage();
        assertNotNull(usage);
        assertEquals(10, usage.getInputTokens());
        assertEquals(5, usage.getOutputTokens());
        assertEquals(Integer.valueOf(15), usage.getTotalTokens());
        assertEquals(Integer.valueOf(3), usage.getCacheTokens());

        List<IContent> history = window.history();
        assertEquals(2, history.size());
        assertTrue(history.get(0) instanceof UserContent);
        assertEquals("hi", ((UserContent) history.get(0)).getContent());
        assertTrue(history.get(1) instanceof AssistantContent);
        assertEquals("hello", ((AssistantContent) history.get(1)).getContent());
        assertTrue(invoker.callIds.isEmpty());
    }

    @Test
    public void converseWithToolRoundTripCountsRoundTripsAndAlignsResults() {
        FakeWindowMemory window = new FakeWindowMemory("c1");
        ScriptedModel model = new ScriptedModel();
        model.script(toolCallResponse("call-1", "get_weather"));
        model.script(contentResponse("done", null, null, null, null));
        RecordingInvoker invoker = new RecordingInvoker();
        DefaultConversationOrchestrator orchestrator = orchestrator(window, model, invoker);

        ConversationResult result = orchestrator.converse(request("c1", "m1", "weather?"));

        assertEquals(OrchestrationStatusEnum.COMPLETED, result.getStatus());
        assertEquals("done", result.getContent());
        assertEquals(1, result.getToolRoundTrips());
        assertEquals(Collections.singletonList("call-1"), invoker.callIds);

        List<IContent> history = window.history();
        assertEquals(4, history.size());
        assertTrue(history.get(1) instanceof ToolCallContent);
        assertTrue(history.get(2) instanceof ToolContent);
        assertEquals("call-1", ((ToolContent) history.get(2)).getToolCallId());
        assertTrue(history.get(3) instanceof AssistantContent);
    }

    @Test
    public void converseFailsWhenToolRoundTripLimitExceeded() {
        FakeWindowMemory window = new FakeWindowMemory("c1");
        ScriptedModel model = new ScriptedModel();
        model.script(toolCallResponse("call-1", "loop"));
        model.script(toolCallResponse("call-2", "loop"));
        RecordingInvoker invoker = new RecordingInvoker();
        DefaultConversationOrchestrator orchestrator = orchestrator(window, model, invoker);

        ConversationRequest request = ConversationRequest.builder()
                .conversationId("c1").model("m1").userInput("go").maxToolRoundTrips(1).build();
        ConversationResult result = orchestrator.converse(request);

        assertEquals(OrchestrationStatusEnum.FAILED, result.getStatus());
        assertNotNull(result.getError());
        assertEquals(ErrorCategory.RESOURCE_LIMIT, result.getError().getCategory());
        assertEquals(ErrorCodes.INVALID_ARGUMENT, result.getError().getCode());
        assertTrue(result.getError().getSafeMessage().contains("tool round trip limit exceeded"));
        assertEquals(Collections.singletonList("call-1"), invoker.callIds);
    }

    @Test
    public void converseRejectsNullRequest() {
        DefaultConversationOrchestrator orchestrator =
                orchestrator(new FakeWindowMemory("c1"), new ScriptedModel(), new RecordingInvoker());
        ClaraException failure = assertThrows(ClaraException.class, () -> orchestrator.converse(null));
        assertEquals(ErrorCodes.INVALID_ARGUMENT, failure.getError().getCode());
    }

    @Test
    public void converseRejectsBlankConversationId() {
        DefaultConversationOrchestrator orchestrator =
                orchestrator(new FakeWindowMemory("c1"), new ScriptedModel(), new RecordingInvoker());
        ClaraException failure = assertThrows(ClaraException.class,
                () -> orchestrator.converse(request("   ", "m1", "hi")));
        assertEquals(ErrorCategory.VALIDATION, failure.getError().getCategory());
    }

    @Test
    public void converseRejectsNullModelOrUserInput() {
        DefaultConversationOrchestrator orchestrator =
                orchestrator(new FakeWindowMemory("c1"), new ScriptedModel(), new RecordingInvoker());

        ClaraException missingModel = assertThrows(ClaraException.class,
                () -> orchestrator.converse(request("c1", null, "hi")));
        assertEquals(ErrorCodes.INVALID_ARGUMENT, missingModel.getError().getCode());

        ClaraException missingInput = assertThrows(ClaraException.class,
                () -> orchestrator.converse(request("c1", "m1", null)));
        assertEquals(ErrorCodes.INVALID_ARGUMENT, missingInput.getError().getCode());
    }

    // ---- helpers -----------------------------------------------------------

    private static DefaultConversationOrchestrator orchestrator(FakeWindowMemory window,
            ScriptedModel model, RecordingInvoker invoker) {
        return new DefaultConversationOrchestrator(
                (conversationId, provider, modelId) -> window,
                nullDepAssembler(),
                new CapabilityDispatcher(model),
                new ToolRoundTripCoordinator(invoker),
                new CallRecordPublisher(null),
                null);
    }

    private static ContextAssembler nullDepAssembler() {
        return new ContextAssembler(
                new WindowCompressionCoordinator(null),
                new SemanticRecallCoordinator(null, null),
                new SkillMatchCoordinator(null, null, null));
    }

    private static ConversationRequest request(String conversationId, String model, String userInput) {
        return ConversationRequest.builder()
                .conversationId(conversationId)
                .model(model)
                .userInput(userInput)
                .capabilityKind(CapabilityKindEnum.CHAT)
                .build();
    }

    private static HttpResponse contentResponse(String content, Integer inputTokens,
            Integer outputTokens, Integer totalTokens, Integer cacheTokens) {
        HttpResponse response = new HttpResponse();
        response.setContent(content);
        response.setInputTokens(inputTokens);
        response.setOutputTokens(outputTokens);
        response.setTotalTokens(totalTokens);
        response.setCachedTokens(cacheTokens);
        return response;
    }

    private static HttpResponse toolCallResponse(String callId, String name) {
        ToolCallFunction function = new ToolCallFunction();
        function.setName(name);
        function.setArguments("{}");
        ToolCall call = new ToolCall();
        call.setId(callId);
        call.setType("function");
        call.setFunction(function);
        HttpResponse response = new HttpResponse();
        response.setToolCalls(Collections.singletonList(call));
        return response;
    }

    private static final class FakeWindowMemory implements IWindowMemory {

        private final String conversationId;
        private final List<IContent> history = new ArrayList<IContent>();

        FakeWindowMemory(String conversationId) {
            this.conversationId = conversationId;
        }

        List<IContent> history() {
            return new ArrayList<IContent>(history);
        }

        @Override
        public String conversationId() {
            return conversationId;
        }

        @Override
        public void append(List<IContent> messages) {
            history.addAll(messages);
        }

        @Override
        public List<IContent> messages() {
            return new ArrayList<IContent>(history);
        }

        @Override
        public List<IContent> window(WindowPolicy policy, ITokenCountEstimator estimator) {
            return new ArrayList<IContent>(history);
        }

        @Override
        public void clear() {
            history.clear();
        }
    }

    private static final class ScriptedModel implements IStreamModel {

        private final Deque<HttpResponse> responses = new ArrayDeque<HttpResponse>();

        void script(HttpResponse response) {
            responses.add(response);
        }

        @Override
        public <R extends IResponse> R chat(IRequest request, Class<R> responseType) {
            return responseType.cast(responses.poll());
        }

        @Override
        public <R extends IResponse> IStreamHandle chat(IRequest request, Class<R> responseType,
                IStreamObserver observer) {
            throw new UnsupportedOperationException("streaming not used in this test");
        }
    }

    private static final class RecordingInvoker implements IToolInvoker {

        private final List<String> callIds = new ArrayList<String>();

        @Override
        public ToolResult invoke(IToolInvocation invocation) {
            callIds.add(invocation.callId());
            return new ToolResult(invocation.callId(), "{\"ok\":true}");
        }
    }
}
