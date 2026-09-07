package com.clara.model.provider.anthropic;

import com.clara.kernel.ClaraError;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCategory;
import com.clara.kernel.ErrorCodes;
import com.clara.model.api.ChatRequest;
import com.clara.model.api.StreamEvent;
import com.clara.model.api.StreamHandle;
import com.clara.model.api.StreamObserver;
import com.clara.model.api.StreamingChatModel;
import com.clara.model.api.ToolCallContent;
import com.clara.model.provider.http.ProviderCredentialResolver;
import com.clara.model.provider.http.ProviderHttpRequest;
import com.clara.model.provider.http.ProviderHttpResponse;
import com.clara.model.provider.http.ProviderSseEvent;
import com.clara.model.provider.http.ProviderSseListener;
import com.clara.model.provider.http.ProviderSseTransport;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * 调用 Anthropic SSE 对话接口，并将内容块、文本增量和工具调用转换为统一流事件。
 *
 * <p>常规用法：注入配置、SSE 传输、租户凭据解析器和执行器后调用 {@link #chat}，由
 * {@link StreamObserver} 顺序消费事件，并通过返回的 {@link StreamHandle} 取消或查询状态。
 *
 * <p>注意：内容块数量和累计字符数必须设置上限；事件顺序、工具参数及结束原因会被严格校验，不完整的流按
 * 协议错误处理。凭据仅在调用期间解析并清理，执行器生命周期仍由调用方负责。
 */
public final class AnthropicStreamingChatModel implements StreamingChatModel {
    private final AnthropicChatConfig config;
    private final ProviderSseTransport transport;
    private final ProviderCredentialResolver credentials;
    private final ExecutorService executor;
    private final int maxContentBlocks;
    private final int maxAccumulatedChars;
    private final AnthropicChatCodec codec = new AnthropicChatCodec();

    public AnthropicStreamingChatModel(AnthropicChatConfig config,
            ProviderSseTransport transport, ProviderCredentialResolver credentials,
            ExecutorService executor, int maxContentBlocks, int maxAccumulatedChars) {
        if (config == null || transport == null || credentials == null || executor == null) {
            throw new IllegalArgumentException("dependencies must not be null");
        }
        if (maxContentBlocks <= 0 || maxAccumulatedChars <= 0) {
            throw new IllegalArgumentException("stream limits must be positive");
        }
        this.config = config;
        this.transport = transport;
        this.credentials = credentials;
        this.executor = executor;
        this.maxContentBlocks = maxContentBlocks;
        this.maxAccumulatedChars = maxAccumulatedChars;
    }

    public StreamHandle chat(final ChatRequest request, final StreamObserver observer) {
        if (request == null || observer == null) {
            throw new IllegalArgumentException("request and observer must not be null");
        }
        request.getContext().getCancellationToken()
                .throwIfCancellationRequested(request.getContext().getTraceId());
        final byte[] payload = codec.encode(request, config, true);
        final AsyncHandle handle = new AsyncHandle(request);
        handle.setFuture(executor.submit(new Runnable() {
            public void run() { execute(request, observer, payload); }
        }));
        return handle;
    }

    private void execute(final ChatRequest request, final StreamObserver observer, byte[] payload) {
        final Emitter emitter = new Emitter(observer);
        char[] secret = null;
        try {
            char[] resolved = credentials.resolve(request.getContext().getTenantId(), config.getSecretRef());
            if (resolved == null || resolved.length == 0) { throw error(
                    ErrorCodes.MODEL_CREDENTIAL_UNAVAILABLE, ErrorCategory.CONFIGURATION, false, null); }
            secret = resolved.clone();
            Map<String, String> headers = new LinkedHashMap<String, String>();
            headers.put("x-api-key", new String(secret));
            headers.put("anthropic-version", config.getApiVersion());
            headers.put("Content-Type", "application/json");
            headers.put("Accept", "text/event-stream");
            final AnthropicStreamAccumulator accumulator = new AnthropicStreamAccumulator(
                    config, maxContentBlocks, maxAccumulatedChars);
            ProviderHttpResponse response = transport.execute(new ProviderHttpRequest("POST",
                    config.getEndpoint(), headers, payload), request.getContext(),
                    new ProviderSseListener() {
                        public boolean onEvent(ProviderSseEvent event) {
                            AnthropicStreamChunk chunk = accumulator.accept(
                                    event.getEvent(), event.getData());
                            emitChunk(emitter, chunk);
                            return !chunk.isComplete();
                        }
                    });
            if (response == null) { throw protocol(); }
            if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
                throw providerFailure(response.getStatusCode());
            }
            if (!accumulator.isComplete()) { throw protocol(); }
        } catch (ClaraException exception) {
            emitter.error(exception.getError());
        } catch (RuntimeException exception) {
            emitter.error(protocol().getError());
        } finally {
            if (secret != null) Arrays.fill(secret, '\0');
        }
    }

    private static void emitChunk(Emitter emitter, AnthropicStreamChunk chunk) {
        if (chunk.getTextDelta() != null && !chunk.getTextDelta().isEmpty())
            emitter.emit(StreamEvent.Type.TEXT_DELTA, chunk.getTextDelta(), null, null, null, null);
        if (chunk.getToolArgumentsDelta() != null && !chunk.getToolArgumentsDelta().isEmpty())
            emitter.emit(StreamEvent.Type.TOOL_ARGUMENT_DELTA, chunk.getToolArgumentsDelta(),
                    chunk.getToolCallId(), chunk.getToolName(), null, null);
        ToolCallContent tool = chunk.getCompletedTool();
        if (tool != null)
            emitter.emit(StreamEvent.Type.TOOL_CALL_COMPLETE, tool.getArguments(),
                    tool.getId(), tool.getName(), null, null);
        if (chunk.isComplete())
            emitter.emit(StreamEvent.Type.RESPONSE_COMPLETE,
                    chunk.getResponse().getFinishReason().name(), null, null,
                    chunk.getResponse(), null);
    }

    private ClaraException providerFailure(int status) {
        ErrorCategory category = status == 401 ? ErrorCategory.AUTHENTICATION
                : status == 403 ? ErrorCategory.AUTHORIZATION
                : status == 429 ? ErrorCategory.RATE_LIMIT : ErrorCategory.EXTERNAL_SERVICE;
        return error(ErrorCodes.MODEL_PROVIDER_FAILED, category,
                status == 429 || status >= 500, String.valueOf(status));
    }

    private ClaraException protocol() {
        return error(ErrorCodes.MODEL_PROTOCOL_ERROR, ErrorCategory.EXTERNAL_SERVICE, false, null);
    }

    private ClaraException error(String code, ErrorCategory category, boolean retryable,
            String providerCode) {
        return new ClaraException(new ClaraError(code, category, code, retryable, null,
                "ANTHROPIC", providerCode, null, Collections.<String, String>emptyMap()));
    }

    /**
     * 表示。
     */
    private static final class Emitter {
        private final StreamObserver observer;
        private long sequence;
        private boolean terminal;
        private Emitter(StreamObserver observer) { this.observer = observer; }
        private void emit(StreamEvent.Type type, String payload, String toolCallId,
                String toolName, com.clara.model.api.ChatResponse response, ClaraError error) {
            if (terminal) return;
            if (type == StreamEvent.Type.RESPONSE_COMPLETE || type == StreamEvent.Type.ERROR)
                terminal = true;
            observer.onEvent(new StreamEvent(type, ++sequence, payload, toolCallId,
                    toolName, response, error));
        }
        private void error(ClaraError error) {
            if (terminal) return;
            try { emit(StreamEvent.Type.ERROR, error.getCode(), null, null, null, error); }
            catch (RuntimeException ignored) { terminal = true; }
        }
    }

    /**
     * 控制并观察正在执行的异步。
     */
    private static final class AsyncHandle implements StreamHandle {
        private final ChatRequest request;
        private volatile Future<?> future;
        private AsyncHandle(ChatRequest request) { this.request = request; }
        private void setFuture(Future<?> future) { this.future = future; }
        public boolean cancel() {
            boolean token = request.getContext().getCancellationToken().cancel();
            Future<?> current = future;
            boolean task = current != null && current.cancel(true);
            return token || task;
        }
        public boolean isDone() {
            Future<?> current = future;
            return current != null && current.isDone();
        }
    }
}
