package com.clara.orchestration;

import com.clara.core.memory.ITokenCountEstimator;
import com.clara.core.memory.IWindowMemory;
import com.clara.core.message.IContent;
import com.clara.dto.memory.ContextControlConfig;
import com.clara.enums.CapabilityKindEnum;
import com.clara.enums.ModelProviderEnum;
import com.clara.exception.ClaraError;
import com.clara.exception.ClaraException;
import com.clara.exception.ErrorCategory;
import com.clara.kernel.http.HttpRequest;
import com.clara.kernel.http.HttpResponse;
import com.clara.kernel.message.delta.AssistantContent;
import com.clara.kernel.message.delta.TokenUsage;
import com.clara.kernel.message.delta.UserContent;
import com.clara.context.ConvContext;
import com.clara.orchestration.api.ConversationRequest;
import com.clara.orchestration.api.ConversationResult;
import com.clara.orchestration.api.OrchestrationStatusEnum;
import com.clara.orchestration.capability.CapabilityDispatcher;
import com.clara.orchestration.context.ContextAssembler;
import com.clara.orchestration.record.CallRecordPublisher;
import com.clara.orchestration.tool.ToolRoundTripCoordinator;

import java.util.Collections;
import java.util.List;

/**
 * 对话编排主入口：驱动完整 Agent Loop。
 *
 * <p>单轮流程：绑定会话上下文 → 恢复历史 → 装配上下文（窗口 / 压缩 / 技能 / 检索注入） → 按能力种类调用
 * 模型 → 记录调用 → 追加历史 → 若含工具调用则执行并回填后再次请求，直至无工具调用 → 封装结果 → 解绑。
 *
 * <p>取消：全链路复用 {@link ConvContext} 的取消令牌，循环体与关键点快速失败；取消后以「已取消」终态收尾。
 */
public final class DefaultConversationOrchestrator {

    private static final int DEFAULT_MAX_TOOL_ROUND_TRIPS = 8;

    private final WindowMemoryFactory windowMemoryFactory;
    private final ContextAssembler contextAssembler;
    private final CapabilityDispatcher capabilityDispatcher;
    private final ToolRoundTripCoordinator toolRoundTripCoordinator;
    private final CallRecordPublisher callRecordPublisher;
    private final ITokenCountEstimator tokenCountEstimator;

    /**
     * 创建对话编排器。
     *
     * @param windowMemoryFactory       按「会话 + 供应商 + 模型」提供窗口记忆实例的工厂，不可为 null
     * @param contextAssembler          上下文装配器，不可为 null
     * @param capabilityDispatcher      多模态能力分派器，不可为 null
     * @param toolRoundTripCoordinator  工具往返协调器，不可为 null
     * @param callRecordPublisher       调用记录发布者，不可为 null
     * @param tokenCountEstimator       token 估算器，可为 null
     */
    public DefaultConversationOrchestrator(WindowMemoryFactory windowMemoryFactory,
            ContextAssembler contextAssembler, CapabilityDispatcher capabilityDispatcher,
            ToolRoundTripCoordinator toolRoundTripCoordinator, CallRecordPublisher callRecordPublisher,
            ITokenCountEstimator tokenCountEstimator) {
        if (windowMemoryFactory == null || contextAssembler == null || capabilityDispatcher == null
                || toolRoundTripCoordinator == null || callRecordPublisher == null) {
            throw new IllegalArgumentException("orchestrator dependencies are invalid");
        }
        this.windowMemoryFactory = windowMemoryFactory;
        this.contextAssembler = contextAssembler;
        this.capabilityDispatcher = capabilityDispatcher;
        this.toolRoundTripCoordinator = toolRoundTripCoordinator;
        this.callRecordPublisher = callRecordPublisher;
        this.tokenCountEstimator = tokenCountEstimator;
    }

    /**
     * 执行一次完整对话编排。
     *
     * @param request 一次对话请求，不可为 null
     * @return 对话结果，非 null
     */
    public ConversationResult converse(ConversationRequest request) {
        if (request == null || request.getConversationId() == null
                || request.getConversationId().trim().isEmpty()) {
            throw OrchestrationErrors.invalidArgument("conversationId must not be blank");
        }
        if (request.getModel() == null || request.getUserInput() == null) {
            throw OrchestrationErrors.invalidArgument("model and userInput are required");
        }
        CapabilityKindEnum capabilityKind = request.getCapabilityKind() == null
                ? CapabilityKindEnum.CHAT : request.getCapabilityKind();
        ContextControlConfig control = request.getControl() == null
                ? ContextControlConfig.defaults() : request.getControl();
        ModelProviderEnum provider = request.getProvider();
        int maxToolRoundTrips = request.getMaxToolRoundTrips() > 0
                ? request.getMaxToolRoundTrips() : DEFAULT_MAX_TOOL_ROUND_TRIPS;

        ConvContext context = new ConvContext(request.getUserId(), request.getConversationId(),
                request.getProviderConfiguration(), capabilityKind);
        IWindowMemory windowMemory = windowMemoryFactory.create(request.getConversationId(),
                provider == null ? null : provider.name(), request.getModel());
        ConvContext.bind(context);
        try {
            windowMemory.append(Collections.singletonList(new UserContent(request.getUserInput())));
            int toolRoundTrips = 0;
            while (true) {
                context.throwIfCancelled();
                ContextAssembler.AssembledContext assembled =
                        contextAssembler.assemble(windowMemory, control, tokenCountEstimator);
                HttpRequest httpRequest = HttpRequest.builder()
                        .model(request.getModel())
                        .messages(assembled.getMessages())
                        .tools(assembled.getTools().isEmpty() ? null : assembled.getTools())
                        .stream(Boolean.valueOf(request.isStream()))
                        .build();
                HttpResponse response = capabilityDispatcher.invoke(httpRequest, request.getObserver());
                callRecordPublisher.publish(httpRequest, response, provider, request.getModel());
                if (response == null) {
                    if (context.isCancelled()) {
                        return cancelled(request.getConversationId(), toolRoundTrips);
                    }
                    return conversationResult(request.getConversationId(), null,
                            OrchestrationStatusEnum.FAILED, toolRoundTrips, null, null);
                }
                if (toolRoundTripCoordinator.hasToolCalls(response)) {
                    if (toolRoundTrips >= maxToolRoundTrips) {
                        throw OrchestrationErrors.toolRoundTripLimitExceeded(maxToolRoundTrips);
                    }
                    List<IContent> toolMessages = toolRoundTripCoordinator.perform(response);
                    windowMemory.append(toolMessages);
                    toolRoundTrips++;
                    continue;
                }
                String content = capabilityDispatcher.extract(capabilityKind, response);
                windowMemory.append(Collections.singletonList(new AssistantContent(content)));
                return conversationResult(request.getConversationId(), content,
                        OrchestrationStatusEnum.COMPLETED, toolRoundTrips, usage(response), null);
            }
        } catch (ClaraException failure) {
            if (context.isCancelled() || failure.getError().getCategory() == ErrorCategory.CANCELLED) {
                return cancelled(request.getConversationId(), 0);
            }
            return conversationResult(request.getConversationId(), null,
                    OrchestrationStatusEnum.FAILED, 0, null, failure.getError());
        } finally {
            ConvContext.clear();
        }
    }

    private static ConversationResult cancelled(String conversationId, int toolRoundTrips) {
        return conversationResult(conversationId, null, OrchestrationStatusEnum.CANCELLED,
                toolRoundTrips, null, null);
    }

    private static ConversationResult conversationResult(String conversationId, String content,
            OrchestrationStatusEnum status, int toolRoundTrips, TokenUsage usage, ClaraError error) {
        return ConversationResult.builder()
                .conversationId(conversationId)
                .content(content)
                .status(status)
                .toolRoundTrips(toolRoundTrips)
                .usage(usage)
                .error(error)
                .build();
    }

    private static TokenUsage usage(HttpResponse response) {
        int input = response.getInputTokens() == null ? 0 : response.getInputTokens().intValue();
        int output = response.getOutputTokens() == null ? 0 : response.getOutputTokens().intValue();
        return new TokenUsage(input, output, response.getTotalTokens(), response.getCachedTokens());
    }

    /**
     * 窗口记忆工厂：按「会话 + 供应商 + 模型」提供窗口记忆实例（{@code IWindowMemory} 为按会话的实例接口，
     * core 未定义工厂接口，由宿主装配提供）。
     */
    @FunctionalInterface
    public interface WindowMemoryFactory {

        /**
         * 提供指定会话的窗口记忆实例。
         *
         * @param conversationId 会话标识，不可为空白
         * @param provider       供应商标识，可为 null
         * @param model          模型标识，不可为 null
         * @return 窗口记忆实例，非 null
         */
        IWindowMemory create(String conversationId, String provider, String model);
    }
}
