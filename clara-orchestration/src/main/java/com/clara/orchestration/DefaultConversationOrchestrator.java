package com.clara.orchestration;

import com.clara.core.memory.ITokenCountEstimator;
import com.clara.core.memory.IWindowMemory;
import com.clara.core.message.IContent;
import com.clara.dto.memory.ContextControlConfig;
import com.clara.dto.tool.ToolCall;
import com.clara.enums.CapabilityKindEnum;
import com.clara.enums.ModelProviderEnum;
import com.clara.enums.RoleEnum;
import com.clara.exception.ClaraError;
import com.clara.exception.ClaraException;
import com.clara.enums.ErrorCategoryEnum;
import com.clara.kernel.http.HttpRequest;
import com.clara.kernel.http.HttpResponse;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.kernel.message.delta.AssistantContent;
import com.clara.kernel.message.delta.TokenUsage;
import com.clara.kernel.message.delta.UserContent;
import com.clara.kernel.message.delta.UserVisionContent;
import com.clara.core.model.IStreamObserver;
import com.clara.context.CancellationToken;
import com.clara.context.ConvContext;
import com.clara.orchestration.api.ConversationRequest;
import com.clara.orchestration.api.ConversationResult;
import com.clara.orchestration.api.OrchestrationStatusEnum;
import com.clara.orchestration.capability.CapabilityDispatcher;
import com.clara.orchestration.context.ContextAssembler;
import com.clara.orchestration.context.AssembledContext;
import com.clara.orchestration.intent.IntentPlanCoordinator;
import com.clara.orchestration.intent.PreparedIntent;
import com.clara.orchestration.record.CallRecordPublisher;
import com.clara.orchestration.tool.StreamingToolCallObserver;
import com.clara.orchestration.tool.ToolRoundTripCoordinator;

import java.util.Collections;
import java.util.List;

/**
 * 对话编排主入口：驱动完整 Agent Loop。
 *
 * <p>单轮流程：绑定会话上下文 → 恢复历史 → 装配上下文（窗口 / 压缩 / 技能 / 检索注入） → 按能力种类调用
 * 模型 → 记录调用 → 追加历史 → 若含工具调用则执行并回填后再次请求，直至无工具调用 → 封装结果 → 解绑。
 *
 * <p>取消：请求携带取消令牌时复用它（见 {@link ConversationRequest#getCancellationToken()}），全链路共享同一
 * 令牌，循环体与关键点快速失败；取消后以「已取消」终态收尾。
 */
public final class DefaultConversationOrchestrator {

    private final WindowMemoryFactory windowMemoryFactory;
    private final ContextAssembler contextAssembler;
    private final CapabilityDispatcher capabilityDispatcher;
    private final ToolRoundTripCoordinator toolRoundTripCoordinator;
    private final CallRecordPublisher callRecordPublisher;
    private final ITokenCountEstimator tokenCountEstimator;
    private final IntentPlanCoordinator intentPlanCoordinator;

    /**
     * 创建对话编排器（不启用意图识别与动态计划）。
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
        this(windowMemoryFactory, contextAssembler, capabilityDispatcher, toolRoundTripCoordinator,
                callRecordPublisher, tokenCountEstimator, null);
    }

    /**
     * 创建对话编排器。
     *
     * @param windowMemoryFactory       按「会话 + 供应商 + 模型」提供窗口记忆实例的工厂，不可为 null
     * @param contextAssembler          上下文装配器，不可为 null
     * @param capabilityDispatcher      多模态能力分派器，不可为 null
     * @param toolRoundTripCoordinator  工具往返协调器，不可为 null
     * @param callRecordPublisher       调用记录发布者，不可为 null
     * @param tokenCountEstimator       token 估算器，可为 null
     * @param intentPlanCoordinator     意图识别与动态计划协调器，可为 null（不启用）
     */
    public DefaultConversationOrchestrator(WindowMemoryFactory windowMemoryFactory,
            ContextAssembler contextAssembler, CapabilityDispatcher capabilityDispatcher,
            ToolRoundTripCoordinator toolRoundTripCoordinator, CallRecordPublisher callRecordPublisher,
            ITokenCountEstimator tokenCountEstimator, IntentPlanCoordinator intentPlanCoordinator) {
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
        this.intentPlanCoordinator = intentPlanCoordinator;
    }

    /**
     * 执行一次完整对话编排。
     *
     * @param request 一次对话请求，不可为 null
     * @return 对话结果，非 null
     */
    public ConversationResult converse(ConversationRequest request) {
        validateRequest(request);
        CapabilityKindEnum capabilityKind = request.getCapabilityKind() == null
                ? CapabilityKindEnum.CHAT : request.getCapabilityKind();
        ContextControlConfig control = resolveControl(request, capabilityKind);
        ModelProviderEnum provider = request.getProvider();
        IStreamObserver hostObserver = request.getObserver();
        ConvContext context = createContext(request, capabilityKind);
        IWindowMemory windowMemory = windowMemoryFactory.create(request.getConversationId(),
                provider == null ? null : provider.name(), request.getModel());
        ConversationPlan plan = new ConversationPlan(request, capabilityKind, control, provider,
                hostObserver, request.getMaxToolRoundTrips());
        ConversationState state = new ConversationState(context, windowMemory, new StringBuilder());
        try (ConvContext.Scope ignored = ConvContext.open(context)) {
            appendUserTurn(plan, state);
            prepareIntent(plan, state);
            return agentLoop(plan, state);
        } catch (ClaraException failure) {
            return failureResult(plan, state, failure);
        }
    }

    private static void validateRequest(ConversationRequest request) {
        if (request == null || request.getConversationId() == null || request.getConversationId().trim().isEmpty()) {
            throw OrchestrationErrors.invalidArgument("conversationId must not be blank");
        }
        if (request.getModel() == null || request.getUserInput() == null) {
            throw OrchestrationErrors.invalidArgument("model and userInput are required");
        }
    }

    private static void appendUserTurn(ConversationPlan plan, ConversationState state) {
        IContent userTurn = userContent(plan.request, plan.capabilityKind);
        if (shouldAppendUserTurn(state.windowMemory, userTurn)) {
            state.windowMemory.append(Collections.singletonList(userTurn));
        }
    }

    /**
     * 在进入 Agent Loop 前识别意图：复杂任务生成并校验执行计划，简单任务直接跳过。
     *
     * <p>计划只作为本轮 system 指令注入，不写入历史；内部结构化调用（工作流节点）通过
     * {@code planningEnabled=false} 跳过这一步。
     */
    private void prepareIntent(ConversationPlan plan, ConversationState state) {
        if (intentPlanCoordinator == null || plan.capabilityKind != CapabilityKindEnum.CHAT
                || Boolean.FALSE.equals(plan.request.getPlanningEnabled())) {
            return;
        }
        state.context.throwIfCancelled();
        PreparedIntent prepared = intentPlanCoordinator.prepare(
                plan.request.getUserInput(), plan.request.getModel());
        state.planPrompt = prepared.prompt();
    }

    private ConversationResult agentLoop(ConversationPlan plan, ConversationState state) {
        int toolRoundTrips = 0;
        while (true) {
            state.context.throwIfCancelled();
            HttpRequest httpRequest = buildRequest(plan, state);
            HttpResponse response = invokeModel(plan, state, httpRequest);
            if (response == null) {
                return missingResponse(plan, state, toolRoundTrips);
            }
            if (!toolRoundTripCoordinator.hasToolCalls(response)) {
                return completedResult(plan, state, response, toolRoundTrips);
            }
            if (plan.maxToolRoundTrips > 0 && toolRoundTrips >= plan.maxToolRoundTrips) {
                throw OrchestrationErrors.toolRoundTripLimitExceeded(plan.maxToolRoundTrips);
            }
            List<IContent> toolMessages = toolRoundTripCoordinator.perform(response);
            state.windowMemory.append(toolMessages);
            toolRoundTrips++;
            String userInputRequest = toolRoundTripCoordinator.userInputRequest(toolMessages);
            if (userInputRequest != null) {
                state.windowMemory.append(Collections.singletonList(new AssistantContent(userInputRequest)));
                return conversationResult(plan.request.getConversationId(), userInputRequest,
                        OrchestrationStatusEnum.COMPLETED, toolRoundTrips, usage(response), null,
                        state.reasoning.toString(), true);
            }
        }
    }

    private HttpRequest buildRequest(ConversationPlan plan, ConversationState state) {
        HttpRequest.HttpRequestBuilder<?, ?> requestBuilder = HttpRequest.builder()
                .model(plan.request.getModel())
                .stream(plan.request.isStream() || plan.hostObserver != null);
        // 只有对话能力下发 messages 与 tools；图片 / 视频生成等非对话能力以「本轮输入」作为生成
        // 提示词，且不得携带对话工具——如火山方舟生图接口不支持 tools，带上会被平台以
        // InvalidParameter 直接拒绝。
        if (plan.capabilityKind == CapabilityKindEnum.CHAT) {
            List<String> extra = state.planPrompt == null
                    ? Collections.emptyList() : Collections.singletonList(state.planPrompt);
            AssembledContext assembled =
                    contextAssembler.assemble(state.windowMemory, plan.control, tokenCountEstimator, extra);
            requestBuilder.messages(assembled.getMessages())
                    .tools(assembled.getTools().isEmpty() ? null : assembled.getTools());
        } else {
            requestBuilder.prompt(plan.request.getUserInput());
        }
        // 图片生成能力把随请求下发的图片引用作为参考图（图生图）；无附件时即文生图。
        if (plan.capabilityKind == CapabilityKindEnum.IMAGE) {
            requestBuilder.image(plan.request.getAttachments());
        }
        return requestBuilder.build();
    }

    private HttpResponse invokeModel(ConversationPlan plan, ConversationState state, HttpRequest httpRequest) {
        StreamingToolCallObserver streamObserver = plan.hostObserver == null
                ? null : new StreamingToolCallObserver(plan.hostObserver);
        HttpResponse response = capabilityDispatcher.invoke(httpRequest, streamObserver);
        mergeStreamedToolCalls(response, streamObserver);
        callRecordPublisher.publish(httpRequest, response, plan.provider, plan.request.getModel());
        appendReasoning(state.reasoning, response);
        return response;
    }

    private static ConversationResult missingResponse(ConversationPlan plan, ConversationState state,
            int toolRoundTrips) {
        if (state.context.isCancelled()) {
            return cancelled(plan.request.getConversationId(), toolRoundTrips);
        }
        return conversationResult(plan.request.getConversationId(), null,
                OrchestrationStatusEnum.FAILED, toolRoundTrips, null, null, state.reasoning.toString());
    }

    private ConversationResult completedResult(ConversationPlan plan, ConversationState state,
            HttpResponse response, int toolRoundTrips) {
        String content = capabilityDispatcher.extract(plan.capabilityKind, response);
        // 只记录有正文的助手回复：正文为空且不带工具调用的 assistant 消息会被平台判为非法
        // （Invalid assistant message: content or tool_calls must be set），因此不写入历史。
        if (content != null && !content.trim().isEmpty()) {
            state.windowMemory.append(Collections.singletonList(new AssistantContent(content)));
        }
        ConversationResult result = conversationResult(plan.request.getConversationId(), content,
                OrchestrationStatusEnum.COMPLETED, toolRoundTrips, usage(response), null,
                state.reasoning.toString());
        result.setGeneratedImages(response.getGeneratedImages());
        return result;
    }

    private static ConversationResult failureResult(ConversationPlan plan, ConversationState state,
            ClaraException failure) {
        if (state.context.isCancelled() || failure.getError().getCategory() == ErrorCategoryEnum.CANCELLED) {
            return cancelled(plan.request.getConversationId(), 0);
        }
        return conversationResult(plan.request.getConversationId(), null,
                OrchestrationStatusEnum.FAILED, 0, null, failure.getError(), state.reasoning.toString());
    }

    /**
     * 单轮编排中不随循环变化的输入。
     */
    private static final class ConversationPlan {
        private final ConversationRequest request;
        private final CapabilityKindEnum capabilityKind;
        private final ContextControlConfig control;
        private final ModelProviderEnum provider;
        private final IStreamObserver hostObserver;
        private final int maxToolRoundTrips;

        private ConversationPlan(ConversationRequest request, CapabilityKindEnum capabilityKind,
                ContextControlConfig control, ModelProviderEnum provider, IStreamObserver hostObserver,
                int maxToolRoundTrips) {
            this.request = request;
            this.capabilityKind = capabilityKind;
            this.control = control;
            this.provider = provider;
            this.hostObserver = hostObserver;
            this.maxToolRoundTrips = maxToolRoundTrips;
        }
    }

    /**
     * 单轮编排中随循环推进的状态。
     */
    private static final class ConversationState {
        private final ConvContext context;
        private final IWindowMemory windowMemory;
        private final StringBuilder reasoning;
        /** 本轮已校验的执行计划文本；简单任务或未启用计划时为 null。 */
        private String planPrompt;

        private ConversationState(ConvContext context, IWindowMemory windowMemory, StringBuilder reasoning) {
            this.context = context;
            this.windowMemory = windowMemory;
            this.reasoning = reasoning;
        }
    }

    /**
     * 本轮用户消息：对话能力携带图片附件时以多模态消息下发（图片识别），其余情况为纯文本消息。
     *
     * <p>图片生成能力（IMAGE）的附件是参考图，由请求的 {@code image} 字段承载，不进入对话消息。
     */
    static IContent userContent(ConversationRequest request, CapabilityKindEnum capabilityKind) {
        List<String> attachments = request.getAttachments();
        if (capabilityKind != CapabilityKindEnum.CHAT || attachments == null || attachments.isEmpty()) {
            return new UserContent(request.getUserInput());
        }
        return new UserVisionContent(request.getUserInput(), attachments);
    }

    /**
     * 判断本轮用户消息要不要追加进窗口记忆：末尾已经是同一条用户消息时跳过。
     *
     * <p>为什么判重：同一份历史里连着两条一模一样的用户消息，只可能来自「上一轮没留下助手回复」——
     * 即模型不可用时的故障转移把同一轮换个模型重发（或上一轮以取消 / 失败收尾）。那种悬空重复会让重试的
     * 提示词里出现两遍同一个问题，也会在历史里越攒越多；正常的连续两轮之间必有助手回复，不会判成重复。
     *
     * @param windowMemory 当前模型对应的窗口记忆，非 null
     * @param userTurn     本轮用户消息，非 null
     * @return 需要追加时返回 true
     */
    static boolean shouldAppendUserTurn(IWindowMemory windowMemory, IContent userTurn) {
        List<IContent> history = windowMemory.messages();
        if (history.isEmpty()) {
            return true;
        }
        IContent last = history.get(history.size() - 1);
        return last.getRoleType() != RoleEnum.USER || !last.equals(userTurn);
    }

    /**
     * 装配本轮会话上下文：请求携带取消令牌时复用它，否则自建。
     *
     * <p>为什么复用：宿主（如控制台 ESC、Web 层的客户端断开）需要在外部置位同一个令牌来终止本轮正在进行的
     * 模型调用与工具执行；若编排层自建令牌，外部便无从干预。
     */
    private static ConvContext createContext(ConversationRequest request, CapabilityKindEnum capabilityKind) {
        CancellationToken cancellationToken = request.getCancellationToken();
        if (cancellationToken == null) {
            return new ConvContext(request.getUserId(), request.getConversationId(),
                    request.getProviderConfiguration(), capabilityKind);
        }
        return new ConvContext(request.getUserId(), request.getConversationId(), null,
                request.getProviderConfiguration(), capabilityKind, cancellationToken);
    }

    /**
     * 解析本轮上下文控制配置：请求显式指定优先，其次取平台配置中该能力的 {@code control} 节点，
     * 都未提供时退回默认配置。
     *
     * <p>为什么回退到平台配置：{@code control}（检索条数、相似度阈值等）本就随平台能力配置声明
     * （{@code ProviderCapabilityConfiguration#contextControlConfig()}），宿主往往只在请求里给模型与输入，
     * 若不回退，平台配置里的 control 会被静默忽略。
     *
     * @param request 当前请求，非 null
     * @param kind    能力种类，非 null
     * @return 上下文控制配置，非 null
     */
    private static ContextControlConfig resolveControl(ConversationRequest request, CapabilityKindEnum kind) {
        if (request.getControl() != null) {
            return request.getControl();
        }
        ProviderConfiguration configuration = request.getProviderConfiguration();
        if (configuration != null) {
            try {
                return configuration.getCapability(kind).contextControlConfig();
            } catch (RuntimeException ignored) {
                // 该能力未配置时退回默认，不阻断对话
            }
        }
        return ContextControlConfig.defaults();
    }

    private static ConversationResult cancelled(String conversationId, int toolRoundTrips) {
        return conversationResult(conversationId, null, OrchestrationStatusEnum.CANCELLED,
                toolRoundTrips, null, null, null);
    }

    /**
     * 把流式拼装出的工具调用补充到响应上。
     *
     * <p>为什么需要：Chat Completions 流式协议把工具调用拆成分片，响应对象不自动累加工具调用；若不补充，
     * 编排层会读不到工具调用，把工具轮误判为最终答复。
     *
     * @param response 本轮响应，可为 null
     * @param observer 流式拼装观察者，可为 null（非流式时）
     */
    private static void mergeStreamedToolCalls(HttpResponse response, StreamingToolCallObserver observer) {
        if (response == null || observer == null) {
            return;
        }
        if (response.getToolCalls() != null && !response.getToolCalls().isEmpty()) {
            return;
        }
        List<ToolCall> assembled = observer.toolCalls();
        if (!assembled.isEmpty()) {
            response.setToolCalls(assembled);
        }
    }

    /**
     * 追加一轮模型响应中的思维链（{@code reasoning}）文本，多轮之间以换行分隔，便于宿主展示与记录。
     *
     * @param reasoning 累积容器，非 null
     * @param response  本轮模型响应，可为 null
     */
    private static void appendReasoning(StringBuilder reasoning, HttpResponse response) {
        if (response == null || response.getReasoning() == null
                || response.getReasoning().trim().isEmpty()) {
            return;
        }
        if (reasoning.length() > 0) {
            reasoning.append('\n');
        }
        reasoning.append(response.getReasoning().trim());
    }

    private static ConversationResult conversationResult(String conversationId, String content,
            OrchestrationStatusEnum status, int toolRoundTrips, TokenUsage usage, ClaraError error,
            String reasoning) {
        return conversationResult(conversationId, content, status, toolRoundTrips, usage, error, reasoning, false);
    }

    /**
     * 构造对话结果，并标记是否因等待人工输入而暂停。
     *
     * <p>暂停时 content 是工作流返回的确认文案；模型本轮没有产生最终正文，宿主需自行展示该文案。
     */
    private static ConversationResult conversationResult(String conversationId, String content,
            OrchestrationStatusEnum status, int toolRoundTrips, TokenUsage usage, ClaraError error,
            String reasoning, boolean pausedForUserInput) {
        return ConversationResult.builder()
                .conversationId(conversationId)
                .content(content)
                .status(status)
                .toolRoundTrips(toolRoundTrips)
                .usage(usage)
                .error(error)
                .reasoning(reasoning)
                .pausedForUserInput(pausedForUserInput)
                .build();
    }

    private static TokenUsage usage(HttpResponse response) {
        int input = response.getInputTokens() == null ? 0 : response.getInputTokens();
        int output = response.getOutputTokens() == null ? 0 : response.getOutputTokens();
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
