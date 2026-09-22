package com.clara.kernel.api;

import com.clara.kernel.http.RawHttpRequest;

/**
 * 请求对象契约：一次能力调用的入参根接口，字段与平台 JSON 的对应关系由字段注解声明。
 *
 * <p>是什么、在哪一层：契约层（{@code com.clara.kernel.api}）。派生链为
 * {@code ModelParameters}（通用参数）→ {@code ImageParameters}（图片生成专有参数）→
 * {@link com.clara.kernel.http.HttpRequest}（统一入参对象）。平台与能力不写在请求对象里，而是随线程
 * 上下文（{@link com.clara.context.ConvContext}）传入：字段上的 {@code @ProviderAdapter} 注解按
 * 「平台 + 能力种类」声明该字段是否下发、用什么线格式名与默认值，因此换平台不需要换请求类型。
 *
 * <p>谁创建、谁调用：由调用方构造并设置字段后交给 {@link IStreamModel} 或
 * {@link com.clara.kernel.api.execute.IHttpExecute} 发起调用；{@link #toRequest()} 由传输实现在发送前调用。
 *
 * <p>使用方法：
 * <pre>{@code
 * HttpRequest request = HttpRequest.builder()
 *         .model(modelId)
 *         .messages(messages)
 *         .stream(Boolean.TRUE)
 *         .build();
 * HttpResponse response = model.chat(request, HttpResponse.class);
 * }</pre>
 *
 * <p>失败语义：{@link #toRequest()} 的失败来自编码步骤——必需入参为 null、字段注解默认值与字段类型不匹配
 * 时抛 {@link IllegalArgumentException}，请求体无法序列化、密钥不可用或平台与能力组合不受支持时抛
 * {@link com.clara.kernel.exception.ClaraException}。
 */
public interface IRequest {

    /**
     * 返回当前请求对象的实际类型。
     *
     * @param <T> 请求类型，需实现 {@link IRequest}
     * @return 运行时请求类型，非 null（实现返回自身的 {@code Class}）
     */
    <T extends IRequest> Class<T> getCls();

    /**
     * 把当前请求对象编码成可直接发送的原始 HTTP 请求。
     *
     * <p>各实现（{@link com.clara.kernel.http.HttpRequest} 等）转调
     * {@code ProviderRequestEncoder.encode(this, providerConfiguration)}：字段按注解规则筛选并序列化，
     * 端点、鉴权头与请求体内容类型取自线程上下文里的平台配置，因此实现要求
     * {@code ConvContext.current()} 非 null——未绑定时取不到平台与能力，编码无法进行。
     *
     * @return 可直接交给传输实现的 HTTP 请求，非 null；成功时为 POST，请求体为筛选后的参数字节
     * @throws NullPointerException      线程未绑定会话上下文（{@code ConvContext.current()} 为 null）时
     * @throws IllegalArgumentException  编码所需入参为 null、当前能力未配置，或注解默认值字面量与字段类型不匹配时
     * @throws com.clara.kernel.exception.ClaraException 请求体无法序列化、密钥不可用或平台与能力组合不受支持时
     */
    RawHttpRequest toRequest();

    /**
     * 返回本次请求期望的响应方式。
     *
     * @return {@code Boolean.TRUE} 表示流式（SSE），{@code Boolean.FALSE} 表示一次性返回；可为 null，此时按
     *         非流式处理——编码器用 {@code Boolean.TRUE.equals(...)} 判断，据此决定请求头的
     *         {@code Accept} 与仅流式字段（如 {@code stream_options.include_usage}）是否下发
     */
    Boolean getStream();
}
