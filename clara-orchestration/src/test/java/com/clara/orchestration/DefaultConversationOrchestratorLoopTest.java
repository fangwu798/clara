package com.clara.orchestration;

import com.clara.context.CancellationToken;
import com.clara.core.memory.IWindowMemory;
import com.clara.core.message.IContent;
import com.clara.core.model.IResponse;
import com.clara.core.model.IStreamHandle;
import com.clara.core.model.IStreamModel;
import com.clara.core.model.IStreamObserver;
import com.clara.core.model.IRequest;
import com.clara.dto.memory.WindowPolicy;
import com.clara.dto.model.StreamEvent;
import com.clara.dto.tool.ToolCall;
import com.clara.dto.tool.ToolCallFunction;
import com.clara.dto.tool.ToolResult;
import com.clara.enums.CapabilityKindEnum;
import com.clara.enums.FinishReasonEnum;
import com.clara.enums.ModelProviderEnum;
import com.clara.enums.StreamEventEnum;
import com.clara.exception.ClaraError;
import com.clara.exception.ClaraException;
import com.clara.exception.ErrorCodes;
import com.clara.kernel.config.ProviderCapabilityConfiguration;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.kernel.credential.ProviderCredentialConfiguration;
import com.clara.kernel.http.HttpRequest;
import com.clara.kernel.http.HttpResponse;
import com.clara.kernel.http.TransportConfig;
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
import com.clara.utils.ExceptionUtils;
import org.junit.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link DefaultConversationOrchestrator} 主链路测试：用假模型入口返回固定响应，验证
 * 「用户消息落窗 → 调用 → 结果封装 → 历史追加」、取消、缺失响应、图片产物与工具往返暂停。
 *
 * <p>不访问网络：{@link IStreamModel} 由测试实现直接返回预置的 {@link HttpResponse}。
 */
public class DefaultConversationOrchestratorLoopTest {

    @Test
    public void completesChatAndAppendsAssistantTurn() {
        HttpResponse response = response("hello", "thinking", 11, 7);
        FakeWindowMemory memory = new FakeWindowMemory("c1");
        FakeStreamModel model = new FakeStreamModel(response);
        DefaultConversationOrchestrator orchestrator = orchestrator(model, memory, invocation -> null);

        ConversationResult result = orchestrator.converse(request("hi", CapabilityKindEnum.CHAT));

        assertEquals(OrchestrationStatusEnum.COMPLETED, result.getStatus());
        assertEquals("hello", result.getContent());
        assertEquals("thinking", result.getReasoning());
        assertEquals(11, result.getUsage().getInputTokens());
        assertEquals(7, result.getUsage().getOutputTokens());
        assertEquals(1, model.requests.size());
        assertEquals(2, memory.messages().size());
        assertFalse(result.isPausedForUserInput());
    }

    @Test
    public void emptyContentIsNotWrittenToHistory() {
        FakeWindowMemory memory = new FakeWindowMemory("c1");
        DefaultConversationOrchestrator orchestrator = orchestrator(
                new FakeStreamModel(response("   ", null, null, null)), memory, invocation -> null);

        ConversationResult result = orchestrator.converse(request("hi", CapabilityKindEnum.CHAT));

        assertEquals(OrchestrationStatusEnum.COMPLETED, result.getStatus());
        assertEquals(1, memory.messages().size());
        assertEquals(0, result.getUsage().getOutputTokens());
    }

    @Test
    public void retriesWithCompactedContextWhenProviderReportsOverflow() {
        FakeWindowMemory memory = new FakeWindowMemory("c1");
        FlakyStreamModel model = new FlakyStreamModel(response("hello", null, 1, 1), 1,
                ExceptionUtils.providerFailure(ModelProviderEnum.DEEPSEEK, 400,
                        "This model's maximum context length is 131072 tokens."));
        DefaultConversationOrchestrator orchestrator = orchestrator(model, memory, invocation -> null);

        ConversationResult result = orchestrator.converse(request("hi", CapabilityKindEnum.CHAT));

        assertEquals(OrchestrationStatusEnum.COMPLETED, result.getStatus());
        assertEquals("hello", result.getContent());
        assertEquals(2, model.requests.size());
    }

    @Test
    public void retriesWhenStreamTerminatesWithErrorEvent() {
        ClaraError error = ExceptionUtils.responseTooLarge().getError();
        StreamErrorModel model = new StreamErrorModel(response("hello", null, 1, 1), 1, error);
        DefaultConversationOrchestrator orchestrator = orchestrator(model, new FakeWindowMemory("c1"),
                invocation -> null);

        ConversationResult result = orchestrator.converse(request("hi", CapabilityKindEnum.CHAT));

        assertEquals(OrchestrationStatusEnum.COMPLETED, result.getStatus());
        assertEquals("hello", result.getContent());
        assertEquals(2, model.requests.size());
        assertNull(((HttpRequest) model.requests.get(0)).getMaxTokens());
        assertEquals(Integer.valueOf(32768), ((HttpRequest) model.requests.get(1)).getMaxTokens());
    }

    @Test
    public void continuesWhenOutputReachesLengthLimit() {
        HttpResponse first = response("part1", null, 10, 10);
        first.setFinishReason(FinishReasonEnum.LENGTH);
        HttpResponse second = response("part2", null, 12, 8);
        SequenceStreamModel model = new SequenceStreamModel(Arrays.asList(first, second));
        FakeWindowMemory memory = new FakeWindowMemory("c1");
        DefaultConversationOrchestrator orchestrator = orchestrator(model, memory, invocation -> null);

        ConversationResult result = orchestrator.converse(request("hi", CapabilityKindEnum.CHAT));

        assertEquals(OrchestrationStatusEnum.COMPLETED, result.getStatus());
        assertEquals("part1part2", result.getContent());
        assertEquals(2, model.requests.size());
        assertEquals(3, memory.messages().size());
    }

    @Test
    public void capsConfiguredMaxTokensByResponseBudget() {
        FakeStreamModel model = new FakeStreamModel(response("hi", null, 1, 1));
        DefaultConversationOrchestrator orchestrator = orchestrator(model, new FakeWindowMemory("c1"),
                invocation -> null);
        ConversationRequest request = ConversationRequest.builder()
                .conversationId("c1")
                .model("deepseek-v4-pro")
                .userInput("hi")
                .capabilityKind(CapabilityKindEnum.CHAT)
                .providerConfiguration(providerConfiguration(393_216))
                .build();

        orchestrator.converse(request);

        assertEquals(Integer.valueOf(65_536), ((HttpRequest) model.requests.get(0)).getMaxTokens());
    }

    @Test
    public void doesNotExecuteTruncatedToolCalls() {
        HttpResponse truncated = response(null, null, 10, 10);
        truncated.setFinishReason(FinishReasonEnum.LENGTH);
        truncated.setToolCalls(Collections.singletonList(toolCall("t1", "file_ops")));
        int[] executed = {0};
        DefaultConversationOrchestrator orchestrator = orchestrator(new FakeStreamModel(truncated),
                new FakeWindowMemory("c1"), invocation -> {
                    executed[0]++;
                    return null;
                });

        ConversationResult result = orchestrator.converse(request("hi", CapabilityKindEnum.CHAT));

        assertEquals(OrchestrationStatusEnum.FAILED, result.getStatus());
        assertEquals(ErrorCodes.MODEL_OUTPUT_TRUNCATED, result.getError().getCode());
        assertEquals(0, executed[0]);
    }

    @Test
    public void doesNotRetryUnrelatedProviderFailure() {
        FakeWindowMemory memory = new FakeWindowMemory("c1");
        FlakyStreamModel model = new FlakyStreamModel(response("hello", null, 1, 1), 1,
                ExceptionUtils.providerFailure(ModelProviderEnum.DEEPSEEK, 400, "invalid parameter: temperature"));
        DefaultConversationOrchestrator orchestrator = orchestrator(model, memory, invocation -> null);

        ConversationResult result = orchestrator.converse(request("hi", CapabilityKindEnum.CHAT));

        assertEquals(OrchestrationStatusEnum.FAILED, result.getStatus());
        assertEquals(1, model.requests.size());
    }

    @Test
    public void failsAfterOverflowRetriesAreExhausted() {
        FakeWindowMemory memory = new FakeWindowMemory("c1");
        FlakyStreamModel model = new FlakyStreamModel(null, 99,
                ExceptionUtils.providerFailure(ModelProviderEnum.DEEPSEEK, 400, "context length exceeded"));
        DefaultConversationOrchestrator orchestrator = orchestrator(model, memory, invocation -> null);

        ConversationResult result = orchestrator.converse(request("hi", CapabilityKindEnum.CHAT));

        assertEquals(OrchestrationStatusEnum.FAILED, result.getStatus());
        assertEquals(3, model.requests.size());
    }

    @Test
    public void missingResponseFailsWithEmptyContent() {
        FakeWindowMemory memory = new FakeWindowMemory("c1");
        DefaultConversationOrchestrator orchestrator = orchestrator(new FakeStreamModel(null), memory,
                invocation -> null);

        ConversationResult result = orchestrator.converse(request("hi", CapabilityKindEnum.CHAT));

        assertEquals(OrchestrationStatusEnum.FAILED, result.getStatus());
        assertNull(result.getContent());
    }

    @Test
    public void cancelledRequestReturnsCancelledWithoutCallingModel() {
        FakeWindowMemory memory = new FakeWindowMemory("c1");
        FakeStreamModel model = new FakeStreamModel(response("hello", null, 1, 1));
        DefaultConversationOrchestrator orchestrator = orchestrator(model, memory, invocation -> null);
        CancellationToken token = new CancellationToken();
        token.cancel();

        ConversationResult result = orchestrator.converse(ConversationRequest.builder()
                .conversationId("c1").model("test-model").userInput("hi")
                .capabilityKind(CapabilityKindEnum.CHAT).cancellationToken(token).build());

        assertEquals(OrchestrationStatusEnum.CANCELLED, result.getStatus());
        assertTrue(model.requests.isEmpty());
    }

    @Test
    public void imageCapabilityUsesPromptAndKeepsGeneratedImages() {
        HttpResponse response = response("data:image/png;base64,AAAA", null, 3, 0);
        response.setGeneratedImages(1);
        FakeWindowMemory memory = new FakeWindowMemory("c1");
        FakeStreamModel model = new FakeStreamModel(response);
        DefaultConversationOrchestrator orchestrator = orchestrator(model, memory, invocation -> null);

        ConversationResult result = orchestrator.converse(request("draw a cat", CapabilityKindEnum.IMAGE));

        assertEquals(OrchestrationStatusEnum.COMPLETED, result.getStatus());
        HttpRequest sent = (HttpRequest) model.requests.get(0);
        assertEquals("draw a cat", sent.getPrompt());
        assertEquals(1, result.getGeneratedImages().intValue());
        assertNull(sent.getMessages());
    }

    @Test
    public void toolResultWaitingForUserPausesTheRound() {
        HttpResponse response = response(null, null, 5, 2);
        ToolCall call = toolCall("call-1", "run_workflow");
        response.setToolCalls(Collections.singletonList(call));
        FakeWindowMemory memory = new FakeWindowMemory("c1");
        DefaultConversationOrchestrator orchestrator = orchestrator(new FakeStreamModel(response), memory,
                invocation -> new ToolResult("call-1",
                        "{\"waitingForUser\":true,\"display\":\"请确认视频参数\"}"));

        ConversationResult result = orchestrator.converse(request("make video", CapabilityKindEnum.CHAT));

        assertEquals(OrchestrationStatusEnum.COMPLETED, result.getStatus());
        assertTrue(result.isPausedForUserInput());
        assertEquals("请确认视频参数", result.getContent());
        assertEquals(1, result.getToolRoundTrips());
    }

    @Test
    public void exceedingToolRoundTripsFails() {
        HttpResponse response = response(null, null, 1, 1);
        response.setToolCalls(Collections.singletonList(toolCall("call-1", "text_search")));
        FakeWindowMemory memory = new FakeWindowMemory("c1");
        DefaultConversationOrchestrator orchestrator = orchestrator(new FakeStreamModel(response), memory,
                invocation -> new ToolResult("call-1", "{\"ok\":true}"));

        ConversationResult result = orchestrator.converse(ConversationRequest.builder()
                .conversationId("c1").model("test-model").userInput("loop")
                .capabilityKind(CapabilityKindEnum.CHAT).maxToolRoundTrips(1).build());

        assertEquals(OrchestrationStatusEnum.FAILED, result.getStatus());
        assertNotNull(result.getError());
    }

    @Test
    public void streamsThroughHostObserver() {
        HttpResponse response = response("streamed", null, 2, 3);
        FakeWindowMemory memory = new FakeWindowMemory("c1");
        FakeStreamModel model = new FakeStreamModel(response);
        DefaultConversationOrchestrator orchestrator = orchestrator(model, memory, invocation -> null);
        List<String> observed = new ArrayList<>();

        ConversationResult result = orchestrator.converse(ConversationRequest.builder()
                .conversationId("c1").model("test-model").userInput("hi")
                .capabilityKind(CapabilityKindEnum.CHAT).stream(true)
                .observer(event -> observed.add(String.valueOf(event.getType()))).build());

        assertEquals(OrchestrationStatusEnum.COMPLETED, result.getStatus());
        assertEquals("streamed", result.getContent());
        assertEquals(1, observed.size());
        assertTrue(model.streamingCalls > 0);
    }

    @Test
    public void rejectsBlankConversationIdAndMissingInput() {
        DefaultConversationOrchestrator orchestrator = orchestrator(
                new FakeStreamModel(response("x", null, 1, 1)), new FakeWindowMemory("c1"), invocation -> null);
        try {
            orchestrator.converse(ConversationRequest.builder().conversationId(" ").model("m").userInput("hi").build());
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage().contains("conversationId"));
        }
        try {
            orchestrator.converse(ConversationRequest.builder().conversationId("c1").model("m").build());
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage().contains("userInput"));
        }
    }

    private static DefaultConversationOrchestrator orchestrator(IStreamModel model, IWindowMemory memory,
            com.clara.core.skill.IToolInvoker invoker) {
        ContextAssembler assembler = new ContextAssembler(
                new WindowCompressionCoordinator(null),
                new SemanticRecallCoordinator(null, null),
                new SkillMatchCoordinator(null, null, null));
        return new DefaultConversationOrchestrator(
                (conversationId, provider, modelName) -> memory,
                assembler,
                new CapabilityDispatcher(model),
                new ToolRoundTripCoordinator(invoker),
                new CallRecordPublisher(null),
                null);
    }

    private static ConversationRequest request(String input, CapabilityKindEnum kind) {
        return ConversationRequest.builder()
                .conversationId("c1")
                .model("test-model")
                .userInput(input)
                .capabilityKind(kind)
                .build();
    }

    private static HttpResponse response(String content, String reasoning, Integer input, Integer output) {
        HttpResponse response = new HttpResponse();
        response.setContent(content);
        response.setReasoning(reasoning);
        response.setInputTokens(input);
        response.setOutputTokens(output);
        response.setTotalTokens(input == null || output == null ? null : input + output);
        return response;
    }

    private static ToolCall toolCall(String id, String name) {
        ToolCallFunction function = new ToolCallFunction();
        function.setName(name);
        function.setArguments("{}");
        ToolCall call = new ToolCall();
        call.setId(id);
        call.setType("function");
        call.setFunction(function);
        return call;
    }

    private static ProviderConfiguration providerConfiguration(int maxTokens) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("maxTokens", maxTokens);
        ProviderCapabilityConfiguration chat = new ProviderCapabilityConfiguration(
                URI.create("https://api.deepseek.com/responses"), null, "deepseek-v4-pro", "RESPONSE",
                null, parameters, null);
        Map<CapabilityKindEnum, ProviderCapabilityConfiguration> capabilities = new LinkedHashMap<>();
        capabilities.put(CapabilityKindEnum.CHAT, chat);
        return new ProviderConfiguration(ModelProviderEnum.DEEPSEEK,
                new ProviderCredentialConfiguration("deepseek", null, "test-key".toCharArray()),
                new TransportConfig(Collections.singleton("https://api.deepseek.com"),
                        10_000, 600_000, 8 * 1024 * 1024, 32 * 1024 * 1024, false),
                capabilities);
    }

    /**
     * 预置响应的假模型入口：同步路径直接返回响应；流式路径先回调终态事件，再返回已完成的句柄。
     */
    private static final class FakeStreamModel implements IStreamModel {

        private final HttpResponse response;
        private final List<IRequest> requests = new ArrayList<>();
        private int streamingCalls;

        private FakeStreamModel(HttpResponse response) {
            this.response = response;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R extends IResponse> R chat(IRequest request, Class<R> responseType) {
            requests.add(request);
            return (R) response;
        }

        @Override
        public <R extends IResponse> IStreamHandle chat(IRequest request, Class<R> responseType,
                IStreamObserver observer) {
            requests.add(request);
            streamingCalls++;
            if (observer != null) {
                observer.onEvent(new StreamEvent(StreamEventEnum.RESPONSE_COMPLETE, 1L, "", null, null,
                        response, null));
            }
            return new IStreamHandle() {
                @Override
                public boolean cancel() {
                    return false;
                }

                @Override
                public boolean isDone() {
                    return true;
                }

                @Override
                public IResponse result() {
                    return response;
                }
            };
        }
    }

    /**
     * 按调用顺序返回预置响应的假模型，用于验证输出截断后的自动续写。
     */
    private static final class SequenceStreamModel implements IStreamModel {

        private final List<HttpResponse> responses;
        private final List<IRequest> requests = new ArrayList<>();
        private int index;

        private SequenceStreamModel(List<HttpResponse> responses) {
            this.responses = responses;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R extends IResponse> R chat(IRequest request, Class<R> responseType) {
            requests.add(request);
            return (R) next();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R extends IResponse> IStreamHandle chat(IRequest request, Class<R> responseType,
                IStreamObserver observer) {
            requests.add(request);
            HttpResponse current = next();
            if (observer != null) {
                observer.onEvent(new StreamEvent(StreamEventEnum.RESPONSE_COMPLETE, 1L, "", null, null,
                        current, null));
            }
            return new IStreamHandle() {
                @Override
                public boolean cancel() {
                    return false;
                }

                @Override
                public boolean isDone() {
                    return true;
                }

                @Override
                public IResponse result() {
                    return current;
                }
            };
        }

        private HttpResponse next() {
            return responses.get(Math.min(index++, responses.size() - 1));
        }
    }

    /**
     * 前 N 次调用抛出预置失败、之后返回成功响应的假模型，用于验证超限恢复与重试上限。
     */
    private static final class FlakyStreamModel implements IStreamModel {

        private final HttpResponse response;
        private final int failures;
        private final ClaraException failure;
        private final List<IRequest> requests = new ArrayList<>();
        private int calls;

        private FlakyStreamModel(HttpResponse response, int failures, ClaraException failure) {
            this.response = response;
            this.failures = failures;
            this.failure = failure;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R extends IResponse> R chat(IRequest request, Class<R> responseType) {
            requests.add(request);
            failWhileNeeded();
            return (R) response;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R extends IResponse> IStreamHandle chat(IRequest request, Class<R> responseType,
                IStreamObserver observer) {
            requests.add(request);
            failWhileNeeded();
            if (observer != null) {
                observer.onEvent(new StreamEvent(StreamEventEnum.RESPONSE_COMPLETE, 1L, "", null, null,
                        response, null));
            }
            return new IStreamHandle() {
                @Override
                public boolean cancel() {
                    return false;
                }

                @Override
                public boolean isDone() {
                    return true;
                }

                @Override
                public IResponse result() {
                    return response;
                }
            };
        }

        private void failWhileNeeded() {
            if (calls++ < failures) {
                throw failure;
            }
        }
    }

    /**
     * 内存窗口：按追加顺序保存消息，窗口策略与估算器不参与断言。
     */
    private static final class FakeWindowMemory implements IWindowMemory {

        private final String conversationId;
        private final List<IContent> history = new ArrayList<>();

        private FakeWindowMemory(String conversationId) {
            this.conversationId = conversationId;
        }

        @Override
        public String conversationId() {
            return conversationId;
        }

        @Override
        public void append(List<IContent> items) {
            history.addAll(items);
        }

        @Override
        public List<IContent> messages() {
            return history;
        }

        @Override
        public List<IContent> window(WindowPolicy policy, com.clara.core.memory.ITokenCountEstimator estimator) {
            return history;
        }

        @Override
        public void clear() {
            history.clear();
        }
    }

    /**
     * 前 N 次以流终态 ERROR 失败、之后返回成功响应的假模型，用于验证「异常被吞进事件流」的回归。
     */
    private static final class StreamErrorModel implements IStreamModel {

        private final HttpResponse response;
        private final ClaraError error;
        private final List<IRequest> requests = new ArrayList<>();
        private int remainingFailures;

        private StreamErrorModel(HttpResponse response, int failures, ClaraError error) {
            this.response = response;
            this.remainingFailures = failures;
            this.error = error;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R extends IResponse> R chat(IRequest request, Class<R> responseType) {
            requests.add(request);
            if (remainingFailures-- > 0) {
                throw new ClaraException(error);
            }
            return (R) response;
        }

        @Override
        public <R extends IResponse> IStreamHandle chat(IRequest request, Class<R> responseType,
                IStreamObserver observer) {
            requests.add(request);
            final boolean failed = remainingFailures-- > 0;
            observer.onEvent(new StreamEvent(
                    failed ? StreamEventEnum.ERROR : StreamEventEnum.RESPONSE_COMPLETE, 1L, "",
                    null, null, failed ? null : response, failed ? error : null));
            return new IStreamHandle() {
                @Override
                public boolean cancel() {
                    return false;
                }

                @Override
                public boolean isDone() {
                    return true;
                }

                @Override
                public IResponse result() {
                    return failed ? null : response;
                }

                @Override
                public ClaraError failure() {
                    return failed ? error : null;
                }
            };
        }
    }
}
