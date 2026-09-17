package com.clara.model.provider.compatible;

import com.clara.kernel.ClaraError;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCategory;
import com.clara.kernel.ErrorCodes;
import com.clara.model.api.*;
import com.clara.model.provider.http.ProviderCredentialResolver;
import com.clara.model.provider.http.ProviderHttpTransport;
import com.clara.model.provider.http.ProviderSseListener;
import com.clara.model.provider.http.event.ProviderSseEvent;
import com.clara.model.provider.http.impl.ProviderHttpRequest;
import com.clara.model.provider.http.impl.ProviderHttpResponse;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * 调用 OpenAI 兼容的 SSE 对话接口，并将文本、推理内容和工具参数增量转换为统一流事件。
 *
 * <p>常规用法：注入配置、SSE 传输、租户凭据解析器和执行器后调用 {@link #chat}，通过
 * {@link StreamObserver} 消费事件，并使用返回的 {@link StreamHandle} 查询完成状态或取消。
 *
 * <p>注意：请求上下文负责租户、截止时间和取消信号。工具调用数量及累计字符数必须设置上限；流必须包含合法
 * 的完成事件，否则按协议错误处理。传入的执行器由调用方管理，本类不会关闭它。
 */
public final class OpenAiCompatibleStreamingChatModel implements StreamingChatModel {
    private final OpenAiCompatibleChatConfig config;
    private final ProviderHttpTransport transport;
    private final ProviderCredentialResolver credentials;
    private final ExecutorService executor;
    private final int maxToolCalls;
    private final int maxAccumulatedChars;
    private final OpenAiCompatibleChatCodec codec = new OpenAiCompatibleChatCodec();

    public OpenAiCompatibleStreamingChatModel(OpenAiCompatibleChatConfig config,
                                              ProviderHttpTransport transport, ProviderCredentialResolver credentials,
                                              ExecutorService executor, int maxToolCalls, int maxAccumulatedChars) {
        if (config == null || transport == null || credentials == null || executor == null) {
            throw new IllegalArgumentException("dependencies must not be null");
        }
        if (maxToolCalls <= 0 || maxAccumulatedChars <= 0) {
            throw new IllegalArgumentException("stream limits must be positive");
        }
        this.config = config;
        this.transport = transport;
        this.credentials = credentials;
        this.executor = executor;
        this.maxToolCalls = maxToolCalls;
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
        Future<?> future = executor.submit(new Runnable() {
            public void run() { execute(request, observer, payload); }
        });
        handle.setFuture(future);
        return handle;
    }

    private void execute(final ChatRequest request, final StreamObserver observer, byte[] payload) {
        final Emitter emitter = new Emitter(observer);
        char[] resolved = null;
        char[] secret = null;
        try {
            resolved = credentials.resolve(request.getContext().getTenantId(), config.getSecretRef());
            if (resolved == null || resolved.length == 0) { throw error(
                    ErrorCodes.MODEL_CREDENTIAL_UNAVAILABLE, ErrorCategory.CONFIGURATION, false, null); }
            secret = resolved.clone();
            Map<String, String> headers = new LinkedHashMap<String, String>();
            headers.put("Authorization", "Bearer " + new String(secret));
            headers.put("Content-Type", "application/json");
            headers.put("Accept", "text/event-stream");
            final OpenAiCompatibleStreamAccumulator accumulator =
                    new OpenAiCompatibleStreamAccumulator(config, maxToolCalls, maxAccumulatedChars);
            ProviderHttpResponse response = transport.execute(new ProviderHttpRequest("POST",
                    config.getEndpoint(), headers, payload), request.getContext(),
                    new ProviderSseListener() {
                        public boolean onEvent(ProviderSseEvent event) {
                            if (!"message".equals(event.getEvent())) { throw protocol(); }
                            OpenAiCompatibleStreamChunk chunk = accumulator.accept(event.getData());
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
            emitter.error(error(ErrorCodes.MODEL_PROTOCOL_ERROR,
                    ErrorCategory.EXTERNAL_SERVICE, false, null).getError());
        } finally {
            if (secret != null) Arrays.fill(secret, '\0');
        }
    }

    private static void emitChunk(Emitter emitter, OpenAiCompatibleStreamChunk chunk) {
        if (chunk.getTextDelta() != null && !chunk.getTextDelta().isEmpty())
            emitter.emit(StreamEvent.Type.TEXT_DELTA, chunk.getTextDelta(), null, null, null, null);
        if (chunk.getReasoningDelta() != null && !chunk.getReasoningDelta().isEmpty())
            emitter.emit(StreamEvent.Type.REASONING_DELTA, chunk.getReasoningDelta(),
                    null, null, null, null);
        for (OpenAiCompatibleStreamChunk.ToolDelta delta : chunk.getToolDeltas()) {
            if (delta.getArgumentsDelta() != null && !delta.getArgumentsDelta().isEmpty())
                emitter.emit(StreamEvent.Type.TOOL_ARGUMENT_DELTA, delta.getArgumentsDelta(),
                        delta.getId(), delta.getName(), null, null);
        }
        if (chunk.isComplete()) {
            ChatResponse response = chunk.getResponse();
            for (Content content : response.getMessage().getContents()) {
                if (content instanceof ToolCallContent) {
                    ToolCallContent call = (ToolCallContent) content;
                    emitter.emit(StreamEvent.Type.TOOL_CALL_COMPLETE, call.getArguments(),
                            call.getId(), call.getName(), null, null);
                }
            }
            emitter.emit(StreamEvent.Type.RESPONSE_COMPLETE,
                    response.getFinishReason().name(), null, null, response, null);
        }
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
                config.getProvider().name(), providerCode, null,
                Collections.<String, String>emptyMap()));
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
                String toolName, ChatResponse response, ClaraError error) {
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

        private void setFuture(Future<?> value) { this.future = value; }

        public boolean cancel() {
            boolean tokenCancelled = request.getContext().getCancellationToken().cancel();
            Future<?> current = future;
            boolean futureCancelled = current != null && current.cancel(true);
            return tokenCancelled || futureCancelled;
        }

        public boolean isDone() {
            Future<?> current = future;
            return current != null && current.isDone();
        }
    }
}
