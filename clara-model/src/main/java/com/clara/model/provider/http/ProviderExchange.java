package com.clara.model.provider.http;

import com.clara.model.provider.http.impl.ProviderHttpResponse;
import com.clara.model.provider.http.impl.ProviderInvoker;

/**
 * 描述一次服务提供商 HTTP 往返的「请求 + 响应」两侧，供同步调用使用。
 *
 * <p>用途：在 {@link ProviderRequestPlan} 的基础上补一个响应解析步骤，使
 * {@link ProviderInvoker#exchange} 能一次完成「发送 → 校验 → 解析」，调用方拿到的是领域对象而不是原始
 * 响应字节。流式调用不需要本接口——事件在传输过程中由监听器消费，响应体为空，因此流式只需实现
 * {@link ProviderRequestPlan} 并使用 {@link ProviderInvoker#exchangeStreaming}。
 *
 * <p>实现通常是一次调用一个实例、不可变，且只允许被调用一次（{@code decode} 只在请求成功后执行一次）。
 *
 * @param <I> 请求对象类型，例如对话请求或图片请求
 * @param <O> 解析结果类型，例如对话响应或图片生成结果
 */
public interface ProviderExchange<I, O> extends ProviderRequestPlan<I> {
    /**
     * 解析本次往返的响应体。
     *
     * <p>只有在请求成功（响应非 null 且状态码为 2xx）后才会被调用一次，因此实现无需再判断状态码。
     *
     * @param response 已通过状态码校验的响应，请求头与响应体均已按配置上限读取完毕
     * @param request  本次往返的请求对象
     * @return 领域对象
     * @throws RuntimeException 响应结构不符合协议时
     */
    O decode(ProviderHttpResponse response, I request);
}
