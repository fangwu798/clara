package com.clara.model.provider.compatible;

import com.clara.kernel.ClaraException;
import com.clara.model.api.*;
import com.clara.model.provider.compatible.config.OpenAiCompatibleChatConfig;
import com.clara.model.provider.compatible.handler.AsyncStreamHandle;
import com.clara.model.provider.compatible.handler.EventEmitter;
import com.clara.model.provider.compatible.tool.StreamChunk;
import com.clara.model.provider.compatible.tool.ToolDelta;
import com.clara.model.provider.http.ProviderCredentialResolver;
import com.clara.model.provider.http.ProviderHttpTransport;
import com.clara.model.utils.ExceptionUtils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * 调用 OpenAI 兼容的 SSE 对话接口，并将文本、推理内容和工具参数增量转换为统一流事件。
 *
 * <p>用途：把 {@link ChatRequest} 以 {@code stream=true} 编码后提交到执行器异步执行；SSE 事件经
 * {@link OpenAiCompatibleStreamAccumulator} 累积，并通过 {@link StreamObserver} 依次推送
 * {@code TEXT_DELTA}、{@code REASONING_DELTA}、{@code TOOL_ARGUMENT_DELTA}、
 * {@code TOOL_CALL_COMPLETE}，最后以 {@code RESPONSE_COMPLETE} 或 {@code ERROR} 收尾。
 *
 * <p>与非流式的分工：编码、凭据解析、请求头、发送与状态码映射等与非流式共用的部分由
 * {@link OpenAiCompatibleChatExchange} 承载（差量只有 {@code stream=true}、{@code Accept} 头与是否传
 * SSE 监听器），本类只负责流式响应处理：把事件交给累加器、按序推送，并把异常转成 {@code ERROR} 事件。
 * 同步调用见 {@link OpenAiCompatibleChatModel}。
 *
 * <p>相关实现细节：事件发射器 {@link EventEmitter} 与异步句柄 {@link AsyncStreamHandle} 放在
 * {@code chat.handler} 包，本类只保留调用流程。
 *
 * <p>常规用法：
 * <pre>{@code
 * StreamingChatModel model = new OpenAiCompatibleStreamingChatModel(
 *         config, transport, credentials, executor, maxToolCalls, maxAccumulatedChars);
 * StreamHandle handle = model.chat(request, observer);
 * // 用 handle.isDone() 查询是否结束，用 handle.cancel() 取消本次流
 * }</pre>
 *
 * <p>执行模型：{@link #chat} 立即返回句柄，真正的网络读取在调用方注入的
 * {@link java.util.concurrent.ExecutorService} 上执行；取消会同时取消调用令牌与底层 Future。
 * 执行器由调用方创建与关闭，本类不接管其生命周期；同一 observer 的事件按序回调，终态事件只发一次。
 * 请求体仍在调用线程上先编码，非法请求会在 {@link #chat} 处直接抛出，不会变成异步 ERROR 事件。
 *
 * <p>注意：请求上下文负责租户、截止时间与取消信号；工具调用数量及累计字符数必须设置上限；流必须包含
 * 合法的完成事件，否则按协议错误处理。异常统一由 {@link ExceptionUtils} 构造，本类不自行拼装错误码与
 * 错误类别。
 */
public final class OpenAiCompatibleStreamingChatModel implements StreamingChatModel {
    /**
     * 对话配置，提供平台、端点、模型 ID 与凭据引用；流式累加器与错误归因需要它。
     */
    private final OpenAiCompatibleChatConfig config;
    /**
     * 编解码与一次往返请求的共用执行体，与非流式共用同一套凭据、请求头与状态码处理。
     */
    private final OpenAiCompatibleChatExchange exchange;
    /**
     * 承载阻塞式流读取的执行器，由调用方创建与关闭。
     */
    private final ExecutorService executor;
    /**
     * 允许的最大工具调用数量。
     */
    private final int maxToolCalls;
    /**
     * 文本、推理与工具参数合计的最大累计字符数。
     */
    private final int maxAccumulatedChars;

    /**
     * 创建流式对话模型。
     *
     * @param config              对话配置，不可为 null
     * @param transport           HTTP 传输器，需支持 SSE，不可为 null
     * @param credentials         凭据解析器，不可为 null
     * @param executor            承载流读取的执行器，不可为 null，生命周期由调用方管理
     * @param maxToolCalls        最大工具调用数量，必须为正
     * @param maxAccumulatedChars 最大累计字符数，必须为正
     * @throws IllegalArgumentException 任一依赖为 null 或上限非正时
     */
    public OpenAiCompatibleStreamingChatModel(OpenAiCompatibleChatConfig config, ProviderHttpTransport transport, ProviderCredentialResolver credentials, ExecutorService executor, int maxToolCalls, int maxAccumulatedChars) {
        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        }
        if (maxToolCalls <= 0 || maxAccumulatedChars <= 0) {
            throw new IllegalArgumentException("stream limits must be positive");
        }
        this.config = config;
        this.exchange = new OpenAiCompatibleChatExchange(config, transport, credentials);
        this.executor = executor;
        this.maxToolCalls = maxToolCalls;
        this.maxAccumulatedChars = maxAccumulatedChars;
    }

    /**
     * 提交一次流式对话调用。
     *
     * <p>立即返回句柄，实际的网络读取与事件推送在执行器线程中完成：增量事件按序回调，最后以
     * {@code RESPONSE_COMPLETE} 或 {@code ERROR} 作为唯一终态事件收尾。
     *
     * @param request  对话请求，不可为 null
     * @param observer 流事件观察者，不可为 null
     * @return 可用于查询完成状态或取消的句柄
     * @throws IllegalArgumentException 任一参数为 null，或请求内容不符合协议时
     */
    public StreamHandle chat(final ChatRequest request, final StreamObserver observer) {
        if (request == null || observer == null) {
            throw new IllegalArgumentException("request and observer must not be null");
        }
        request.getContext().getCancellationToken().throwIfCancellationRequested(request.getContext().getTraceId());
        final byte[] payload = exchange.encode(request, true);
        final AsyncStreamHandle handle = new AsyncStreamHandle(request);
        Future<?> future = executor.submit(() -> execute(request, observer, payload));
        handle.setFuture(future);
        return handle;
    }

    /**
     * 在执行器线程中完成一次完整的流式调用。
     *
     * <p>把每个 SSE 事件交给累加器并推送增量，读取结束后校验流已收尾；任何异常都会转换为 ERROR 事件。
     * 凭据的解析与清零由 {@link OpenAiCompatibleChatExchange#send} 负责，本类不接触密钥。
     *
     * @param request  对话请求
     * @param observer 流事件观察者
     * @param payload  已在调用线程编码好的请求体
     */
    private void execute(final ChatRequest request, final StreamObserver observer, byte[] payload) {
        final EventEmitter emitter = new EventEmitter(observer);
        try {
            final OpenAiCompatibleStreamAccumulator accumulator = new OpenAiCompatibleStreamAccumulator(config, maxToolCalls, maxAccumulatedChars);
            exchange.send(request, payload, event -> {
                if (!"message".equals(event.getEvent())) {
                    throw ExceptionUtils.protocolError(config.getProvider());
                }
                StreamChunk chunk = accumulator.accept(event.getData());
                emitChunk(emitter, chunk);
                return !chunk.isComplete();
            });
            if (!accumulator.isComplete()) throw ExceptionUtils.protocolError(config.getProvider());
        } catch (ClaraException exception) {
            emitter.error(exception.getError());
        } catch (RuntimeException exception) {
            emitter.error(ExceptionUtils.protocolError(config.getProvider()).getError());
        }
    }

    /**
     * 把增量块转换为流事件并按序推送。
     *
     * <p>文本、推理与工具参数增量分别发出对应事件；块收尾时先补齐各工具调用的完整参数事件，
     * 再发出携带完整响应的 {@code RESPONSE_COMPLETE}。
     *
     * @param emitter 事件发射器
     * @param chunk   本次增量块
     */
    private static void emitChunk(EventEmitter emitter, StreamChunk chunk) {
        if (chunk.getTextDelta() != null && !chunk.getTextDelta().isEmpty()) {
            emitter.emit(StreamEvent.Type.TEXT_DELTA, chunk.getTextDelta(), null, null, null, null);
        }
        if (chunk.getReasoningDelta() != null && !chunk.getReasoningDelta().isEmpty()) {
            emitter.emit(StreamEvent.Type.REASONING_DELTA, chunk.getReasoningDelta(), null, null, null, null);
        }
        for (ToolDelta delta : chunk.getToolDeltas()) {
            if (delta.getArgumentsDelta() != null && !delta.getArgumentsDelta().isEmpty()) {
                emitter.emit(StreamEvent.Type.TOOL_ARGUMENT_DELTA, delta.getArgumentsDelta(), delta.getId(), delta.getName(), null, null);
            }
        }
        if (chunk.isComplete()) {
            ChatResponse response = chunk.getResponse();
            for (Content content : response.getMessage().getContents()) {
                if (content instanceof ToolCallContent) {
                    ToolCallContent call = (ToolCallContent) content;
                    emitter.emit(StreamEvent.Type.TOOL_CALL_COMPLETE, call.getArguments(), call.getId(), call.getName(), null, null);
                }
            }
            emitter.emit(StreamEvent.Type.RESPONSE_COMPLETE, response.getFinishReason().name(), null, null, response, null);
        }
    }
}
