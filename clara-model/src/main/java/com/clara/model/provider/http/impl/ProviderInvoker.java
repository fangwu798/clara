package com.clara.model.provider.http.impl;

import com.clara.kernel.InvocationContext;
import com.clara.model.api.ModelProvider;
import com.clara.model.provider.http.*;
import com.clara.model.provider.http.impl.ProviderHttpRequest;
import com.clara.model.provider.http.impl.ProviderHttpResponse;
import com.clara.model.utils.ExceptionUtils;
import com.clara.model.utils.StringUtils;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 执行一次服务提供商 HTTP 往返的唯一入口：解析凭据、组装请求头、发送、校验状态码。
 *
 * <p>用途：对话（同步与流式）、图片、音频与视频任务的每次往返都走本类，避免每个能力各写一份
 * 「取密钥 → 拼 Authorization → 建请求 → 判 2xx」的重复代码。调用方只需用
 * {@link ProviderRequestPlan} 描述自己的请求差异，用 {@link ProviderExchange} 描述如何解析响应。
 *
 * <p>三种用法：
 * <pre>{@code
 * // 1) 声明式同步往返：发送 + 校验 + 解析一步到位
 * ChatResponse response = invoker.exchange(request, request.getContext(), exchange);
 *
 * // 2) 声明式流式往返：响应体由监听器消费，方法在流读完后返回
 * invoker.exchangeStreaming(request, request.getContext(), plan, listener);
 *
 * // 3) 命令式一次性往返：任务轮询、取消这类不做解析的调用
 * ProviderHttpResponse response = invoker.execute("GET", endpoint, null, new byte[0], null, context);
 * }</pre>
 *
 * <p>统一承担的职责：
 * <ul>
 *   <li>取消检查：发送前先看调用上下文是否已取消；</li>
 *   <li>凭据：按租户与引用名解析，解析失败抛配置类错误；解析结果克隆后使用，并在 {@code finally}
 *       中清零，调用方拿不到密钥；</li>
 *   <li>请求头：鉴权头 → Content-Type → Accept → 平台附加头，后者同名时覆盖前者；</li>
 *   <li>响应：为 null 视为协议错误；非 2xx 按状态码映射为可重试语义的平台失败错误，响应体不写入异常。</li>
 * </ul>
 *
 * <p>注意：本类不感知具体能力，也不做任何 JSON 解析与资产落盘；实例不可变、线程安全，可被同一平台的
 * 多个模型共享（与 {@link ProviderHttpTransport} 的生命周期一致）。
 */
public final class ProviderInvoker {
    /**
     * 平台标识，用于错误归因。
     */
    private final ModelProvider provider;
    /**
     * 凭据引用名，真实密钥在每次调用时解析。
     */
    private final String secretRef;
    /**
     * HTTP 传输器，同步走两参重载，流式走带监听器的重载。
     */
    private final ProviderHttpTransport transport;
    /**
     * 凭据解析器，按租户与引用名换取密钥。
     */
    private final ProviderCredentialResolver credentials;

    /**
     * 创建往返执行器。
     *
     * @param provider    平台，不可为 null
     * @param secretRef   凭据引用名，不可为空白
     * @param transport   HTTP 传输器，不可为 null
     * @param credentials 凭据解析器，不可为 null
     * @throws IllegalArgumentException 任一依赖为 null 或引用名为空白时
     */
    public ProviderInvoker(ModelProvider provider, String secretRef, ProviderHttpTransport transport,
            ProviderCredentialResolver credentials) {
        if (provider == null || transport == null || credentials == null) {
            throw new IllegalArgumentException("dependencies must not be null");
        }
        this.provider = provider;
        this.secretRef = StringUtils.requireNotBlank(secretRef, "secretRef");
        this.transport = transport;
        this.credentials = credentials;
    }

    /**
     * 执行一次声明式同步往返：发送、校验状态码，再交给 {@code exchange} 解析响应。
     *
     * @param request  请求对象，传给 {@code exchange} 用于组装与解析
     * @param context  调用上下文，提供租户、超时与取消
     * @param exchange 请求与响应两侧的描述
     * @param <I>      请求对象类型
     * @param <O>      解析结果类型
     * @return 解析后的领域对象
     * @throws com.clara.kernel.ClaraException 凭据不可用、传输失败、响应为 null、平台返回非 2xx
     *                                         或响应结构不符合协议时
     */
    public <I, O> O exchange(I request, InvocationContext context, ProviderExchange<I, O> exchange) {
        if (exchange == null) {
            throw new IllegalArgumentException("exchange must not be null");
        }
        return exchange.decode(send(request, context, exchange, null), request);
    }

    /**
     * 执行一次声明式流式往返：响应体由监听器在读取过程中消费，返回时流已读完。
     *
     * <p>与同步往返的区别只有一处——带监听器调用传输器，并期望平台按 SSE 返回。是否声明
     * {@code Accept: text/event-stream} 由 {@code plan} 决定；事件如何解析、如何累加属于能力自身。
     *
     * @param request  请求对象
     * @param context  调用上下文
     * @param plan     请求侧描述，通常是流式对话的请求计划
     * @param listener SSE 事件监听器，不可为 null
     * @param <I>      请求对象类型
     * @return 原始响应（流式成功时响应体为空，非 2xx 时已抛出异常）
     * @throws IllegalArgumentException      监听器为 null 时
     * @throws UnsupportedOperationException 传输器未配置 SSE 时
     * @throws com.clara.kernel.ClaraException 凭据不可用、传输失败或平台返回非 2xx 时
     */
    public <I> ProviderHttpResponse exchangeStreaming(I request, InvocationContext context,
            ProviderRequestPlan<I> plan, ProviderSseListener listener) {
        if (plan == null || listener == null) {
            throw new IllegalArgumentException("plan and listener must not be null");
        }
        return send(request, context, plan, listener);
    }

    /**
     * 执行一次命令式往返：只发送并校验状态码，不做任何解析。
     *
     * <p>适用于任务轮询、任务取消这类「只想拿到原始响应」的调用；需要解析结构时用
     * {@link #exchange} 声明式往返。
     *
     * @param method       HTTP 方法，仅支持 GET/POST/DELETE
     * @param endpoint     目标端点
     * @param contentType  请求体内容类型，null 表示不发送该头
     * @param body         请求体，GET/DELETE 必须为空
     * @param extraHeaders 平台附加请求头，可为 null
     * @param context      调用上下文
     * @return 平台响应
     * @throws com.clara.kernel.ClaraException 凭据不可用、传输失败、响应为 null 或平台返回非 2xx 时
     */
    public ProviderHttpResponse execute(String method, URI endpoint, String contentType, byte[] body,
            Map<String, String> extraHeaders, InvocationContext context) {
        return send(null, context, new RawPlan(method, endpoint, contentType, body, extraHeaders), null);
    }

    /**
     * 发送一次请求并完成状态码校验，供三种用法共用。
     *
     * @param request  请求对象，命令式往返时为 null
     * @param context  调用上下文
     * @param plan     请求侧描述
     * @param listener SSE 监听器，同步往返时为 null
     * @param <I>      请求对象类型
     * @return 已通过状态码校验的响应
     */
    private <I> ProviderHttpResponse send(I request, InvocationContext context, ProviderRequestPlan<I> plan,
            ProviderSseListener listener) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        context.getCancellationToken().throwIfCancellationRequested(context.getTraceId());
        char[] resolved = credentials.resolve(context.getTenantId(), secretRef);
        if (resolved == null || resolved.length == 0) {
            throw ExceptionUtils.credentialUnavailable(provider);
        }
        char[] secret = resolved.clone();
        try {
            Map<String, String> headers = new LinkedHashMap<String, String>();
            headers.putAll(plan.authentication(new String(secret), request));
            String contentType = plan.contentType(request);
            if (contentType != null) {
                headers.put("Content-Type", contentType);
            }
            headers.put("Accept", plan.accept(request));
            headers.putAll(plan.additionalHeaders(request));
            ProviderHttpRequest httpRequest = new ProviderHttpRequest(plan.method(request),
                    plan.endpoint(request), headers, plan.payload(request));
            ProviderHttpResponse response = listener == null
                    ? transport.execute(httpRequest, context)
                    : transport.execute(httpRequest, context, listener);
            if (response == null) {
                throw ExceptionUtils.protocolError(provider);
            }
            if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
                throw ExceptionUtils.providerFailure(provider, response.getStatusCode());
            }
            return response;
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    /**
     * 命令式往返的请求描述：字段全部由调用方显式给出，不依赖请求对象。
     */
    private static final class RawPlan implements ProviderRequestPlan<Void> {
        private final String method;
        private final URI endpoint;
        private final String contentType;
        private final byte[] body;
        private final Map<String, String> extraHeaders;

        RawPlan(String method, URI endpoint, String contentType, byte[] body, Map<String, String> extraHeaders) {
            this.method = method;
            this.endpoint = endpoint;
            this.contentType = contentType;
            this.body = body == null ? new byte[0] : body;
            this.extraHeaders = extraHeaders == null ? Collections.<String, String>emptyMap() : extraHeaders;
        }

        public URI endpoint(Void request) {
            return endpoint;
        }

        public byte[] payload(Void request) {
            return body;
        }

        public String method(Void request) {
            return method;
        }

        public String contentType(Void request) {
            return contentType;
        }

        public Map<String, String> additionalHeaders(Void request) {
            return extraHeaders;
        }
    }
}
