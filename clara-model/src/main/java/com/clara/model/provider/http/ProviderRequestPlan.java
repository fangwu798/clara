package com.clara.model.provider.http;

import com.clara.model.provider.http.impl.ProviderHttpRequest;
import com.clara.model.provider.http.impl.ProviderInvoker;

import java.net.URI;
import java.util.Collections;
import java.util.Map;

/**
 * 描述一次服务提供商 HTTP 往返的「请求侧」：打到哪、用什么方法、发什么、怎么鉴权。
 *
 * <p>用途：把「凭据解析 → 组装请求头 → 发送 → 校验状态码」这套每个能力都要写一遍的流程集中到
 * {@link ProviderInvoker}，各能力只需实现本接口描述自己的请求差异——JSON 还是 multipart、同步还是流式、
 * Bearer 还是平台自定义鉴权头。实现通常是一次调用一个实例、不可变、不跨线程共享。
 *
 * <p>默认值即 OpenAI 兼容家族最常见的形态：{@code POST} + {@code application/json} +
 * {@code Bearer} 鉴权。需要不同的行为时只覆盖对应方法：
 * <ul>
 *   <li>{@link #authentication}：Anthropic 需要 {@code x-api-key} 与 {@code anthropic-version}；</li>
 *   <li>{@link #accept}：流式调用返回 {@code text/event-stream}；</li>
 *   <li>{@link #contentType}：multipart 上传返回 multipart 的内容类型，返回 null 表示不发送该头；</li>
 *   <li>{@link #additionalHeaders}：平台要求的幂等键、异步开关等附加头。</li>
 * </ul>
 *
 * <p>注意：{@code payload}、鉴权头与附加头最终都会交给 {@link ProviderHttpRequest} 校验——方法仅允许
 * GET/POST/DELETE、GET/DELETE 不得带请求体、不得出现重复头名，也不得占用 host、content-length、
 * connection、transfer-encoding 这些由传输层管理的头。
 *
 * @param <I> 请求对象类型，例如对话请求或图片请求
 */
public interface ProviderRequestPlan<I> {
    /**
     * 本次往返的目标端点。
     *
     * @param request 请求对象
     * @return 绝对 URI
     */
    URI endpoint(I request);

    /**
     * 本次往返的请求体：已编码的 JSON 字节或已收尾的 multipart 字节。
     *
     * @param request 请求对象
     * @return 请求体字节，GET/DELETE 必须为空数组
     */
    byte[] payload(I request);

    /**
     * HTTP 方法。
     *
     * @param request 请求对象
     * @return 方法名，默认 POST
     */
    default String method(I request) {
        return "POST";
    }

    /**
     * 请求体的内容类型。
     *
     * @param request 请求对象
     * @return 内容类型，默认 JSON；返回 null 表示不发送该头（适用于无请求体的 GET/DELETE）
     */
    default String contentType(I request) {
        return "application/json";
    }

    /**
     * 期望的响应内容类型。
     *
     * @param request 请求对象
     * @return Accept 头取值，默认 JSON；流式调用应为 {@code text/event-stream}
     */
    default String accept(I request) {
        return "application/json";
    }

    /**
     * 组装鉴权请求头。
     *
     * @param secret  已解析出的密钥明文，仅在本方法调用期间有效
     * @param request 请求对象
     * @return 鉴权相关请求头，默认 {@code Authorization: Bearer <secret>}
     */
    default Map<String, String> authentication(String secret, I request) {
        return Collections.singletonMap("Authorization", "Bearer " + secret);
    }

    /**
     * 组装平台附加请求头。
     *
     * <p>在鉴权头与 Content-Type/Accept 之后合并，因此同名时以本方法返回的值为准。
     *
     * @param request 请求对象
     * @return 附加请求头，默认无
     */
    default Map<String, String> additionalHeaders(I request) {
        return Collections.emptyMap();
    }
}
