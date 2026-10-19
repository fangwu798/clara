package com.clara.core.model;

import com.clara.core.transport.RawHttpResponse;

/**
 * 响应对象契约：一次能力调用的出参根接口，字段与平台 JSON 的对应关系由字段注解声明。
 *
 * <p>是什么、在哪一层：契约层（{@code com.clara.core.model}）。当前唯一实现是
 * {@link com.clara.kernel.http.HttpResponse}——所有平台、所有能力的返回都解到同一个响应对象上，字段上的
 * {@code @ResponseAdapter} 注解按「平台 + 能力种类」分别声明同步取值路径与流式增量路径，因此响应侧也不
 * 需要为每个平台写实现类。
 *
 * <p>谁创建、谁调用：由 {@link ProviderResponseDecoder} 在解码时实例化并
 * 逐个字段回填（要求响应类型有无参构造器），注解匹配所需的平台与能力取自线程上下文
 * （{@link com.clara.context.ConvContext}）；调用方只读取字段，不自行构造，也不跨线程共享同一实例。
 */
public interface IResponse {
    /**
     * 返回当前响应对象的实际类型。
     *
     * @param <T> 响应类型，需实现 {@link IResponse}
     * @return 运行时响应类型，非 null（实现返回自身的 {@code Class}）
     */
    <T extends IResponse> Class<T> getCls();

    /**
     * 返回本次响应对应的原始 HTTP 响应。
     *
     * @return 原始 HTTP 响应（状态码、响应头与响应体）；由解码器在解码开始时回填，未经解码的实例上可为 null
     */
    RawHttpResponse toResponse();
}
