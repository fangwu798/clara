package com.clara.model.provider.http;

import com.clara.kernel.InvocationContext;
import com.clara.model.provider.http.impl.ProviderHttpRequest;
import com.clara.model.provider.http.impl.ProviderHttpResponse;

/**
 * 执行一次服务提供商的 HTTP 调用，覆盖同步与 SSE 流式两种响应处理方式。
 *
 * <p>用途：为所有模型适配器提供统一的请求发送能力，由实现类集中处理端点白名单校验、连接与读取超时、
 * 请求/响应体积上限、取消检查与错误码映射，使适配器只关注协议编解码。
 *
 * <p>接口只有一个抽象方法，两种响应处理方式由 {@code listener} 区分：
 * <ul>
 *   <li>{@code listener == null}：同步调用，等待并读取完整响应体，适用于对话、向量、图片、语音等
 *       一次性返回的接口；</li>
 *   <li>{@code listener != null}：SSE 流式调用，边读边解析事件并回调监听器，适用于流式对话接口。</li>
 * </ul>
 * 同步调用可直接使用便捷重载 {@link #execute(ProviderHttpRequest, InvocationContext)}，无需显式传
 * {@code null}。
 *
 * <p>使用规范：
 * <ul>
 *   <li>实现必须无状态且线程安全，可被多个适配器共享；不要在其中缓存请求级数据。</li>
 *   <li>失败时抛出 {@code ClaraException} 并带上明确的错误码与是否可重试标记，不要返回 {@code null}，
 *       也不要向上抛出裸 {@code IOException}，便于上层统一重试与错误映射。</li>
 *   <li>调用方需保证请求端点已在配置白名单内，实现仍会再次校验并拒绝越权端点。</li>
 *   <li>非 2xx 不会抛异常，而是带着错误响应体正常返回，由调用方按状态码决定错误语义。</li>
 *   <li>不支持流式的实现只需实现抽象方法，并在 {@code listener != null} 时抛出
 *       {@link UnsupportedOperationException}。</li>
 *   <li>可通过实现装饰器包装本接口来增强能力（如请求日志、指标采集），但不得改变请求/响应语义。</li>
 * </ul>
 *
 * @see ProviderCredentialResolver
 * @see ProviderSseListener
 */
public interface ProviderHttpTransport {
    /**
     * 执行调用：{@code listener} 为 null 表示同步读取完整响应体，否则按 SSE 流式解析并回调。
     *
     * @param request  HTTP 请求，方法、端点、请求头与请求体均由不可变对象承载
     * @param context  调用上下文，提供租户、超时、取消与 trace 信息
     * @param listener SSE 事件监听器；为 null 时执行同步调用
     * @return 同步调用返回包含完整响应体的响应；流式调用成功时响应体为空、非 2xx 时携带错误响应体
     * @throws IllegalArgumentException      参数不合法时
     * @throws UnsupportedOperationException 传入监听器（流式调用）但实现不支持 SSE 时
     */
    ProviderHttpResponse execute(ProviderHttpRequest request, InvocationContext context, ProviderSseListener listener);

    /**
     * 同步执行调用，等价于 {@link #execute(ProviderHttpRequest, InvocationContext, ProviderSseListener)
     * execute(request, context, null)}。
     *
     * @param request HTTP 请求
     * @param context 调用上下文，提供租户、超时、取消与 trace 信息
     * @return HTTP 响应，包含状态码、响应头与完整响应体
     */
    default ProviderHttpResponse execute(ProviderHttpRequest request, InvocationContext context) {
        return execute(request, context, null);
    }
}
