package com.clara.kernel.api.execute;

import com.clara.kernel.api.IResponse;
import com.clara.kernel.http.RawHttpResponse;
import com.clara.kernel.message.provider.StreamAssembler;

/**
 * 响应解码器契约：把平台返回的原始响应 / SSE 流解码为统一响应对象。
 *
 * <p>不同协议（Chat Completions / Responses）对同一响应对象的取值路径不同，因此本接口有多个实现；
 * 调用方通过协议分派取得对应实现，无需感知协议差异。
 */
public interface IResponseDecoder {

    /**
     * 把一次同步响应解码为响应对象。
     *
     * @param raw          原始 HTTP 响应，不可为 null
     * @param responseType 响应类型，必须有可访问的无参构造器，不可为 null
     * @param <R>          响应类型，需实现 {@link IResponse}
     * @return 已填充的响应对象，非 null
     */
    <R extends IResponse> R decode(RawHttpResponse raw, Class<R> responseType);

    /**
     * 创建流式累加器：逐条喂入 SSE {@code data}，增量按注解累加。
     *
     * @param responseType 响应类型，必须有可访问的无参构造器，不可为 null
     * @param <R>          响应类型，需实现 {@link IResponse}
     * @return 累加器，非 null
     */
    <R extends IResponse> StreamAssembler<R> streamAssembler(Class<R> responseType);
}
