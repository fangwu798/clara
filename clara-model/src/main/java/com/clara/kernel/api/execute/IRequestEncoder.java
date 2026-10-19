package com.clara.kernel.api.execute;

import com.clara.core.model.IRequest;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.core.transport.RawHttpRequest;

/**
 * 请求编码器契约：把 {@link IRequest} 派生对象编码成可直接发送的 {@link RawHttpRequest}。
 *
 * <p>不同协议（Chat Completions / Responses）对同一请求对象的线格式不同，因此本接口有多个实现；
 * 调用方通过协议分派取得对应实现，无需感知协议差异。平台与能力取自线程上下文（{@code ConvContext}），
 * 字段与平台 JSON 的对应关系由字段注解声明。
 *
 * <p>实现应无状态、线程安全，可被多线程并发调用。
 */
public interface IRequestEncoder {

    /**
     * 按平台与协议规则编码一次能力调用。
     *
     * @param request               请求对象，不可为 null
     * @param providerConfiguration 平台配置，提供平台、端点与协议，不可为 null
     * @return 可直接交给传输实现的 HTTP 请求，非 null
     */
    RawHttpRequest encode(IRequest request, ProviderConfiguration providerConfiguration);
}
