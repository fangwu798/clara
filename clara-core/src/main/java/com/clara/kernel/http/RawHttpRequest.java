package com.clara.kernel.http;

import lombok.Data;

import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 线格式请求：封装一次服务提供商 HTTP 请求的方法、端点、请求头与请求体。
 *
 * <p>是什么与所属层：本类属于 {@code clara-core} 的 HTTP 层（{@code com.clara.kernel.http}），是实现层
 * 不可变值对象，不是模型 API 契约；它对一次 HTTP 请求的方法、端点、请求头与请求体做一次性校验，使传输实现
 * 只面对已合法化的输入。
 *
 * <p>谁创建谁调用：由请求对象的 {@link com.clara.kernel.api.IRequest#toRequest()} 创建（实现里交给
 * {@code ProviderRequestEncoder} 按字段注解编码），再交给传输实现读取（如 Jdk8HttpTransport）。适配器不自行构造本类。
 *
 * <p>使用方法：传输实现依次调用 {@code getMethod()}、{@code getEndpoint()}、{@code getHeaders()}、
 * {@code getBody()} 组装连接并发送。本类没有 close 或释放动作，也不持有连接、流等需要回收的资源，构造完成
 * 即可安全传递；{@code getBody()} 每次返回副本，用完即可丢弃。
 *
 * <p>生命周期与线程安全：实例不可变（字段均为 final，请求头被冻结为不可修改映射，请求体在构造时拷贝、
 * 取值时再次拷贝），构造完成后可安全跨线程共享。
 *
 * <p>失败语义：构造时校验不通过抛 {@link IllegalArgumentException}（方法非法、端点为空、GET/DELETE 带
 * 请求体、头名或头值非法、头名重复、占用传输层管理的头），属于调用方编程错误，不可重试；构造成功后的
 * 读取方法不会抛异常，也不会返回 null（{@code getBody()} 返回的数组可能为空数组，但不为 null）。
 */
@Data
public final class RawHttpRequest {
    /**
     * HTTP 方法，已归一化为大写。
     *
     * <p>取值：仅允许 {@code GET}、{@code POST}、{@code DELETE}；无默认值，必填且不可为 null。
     */
    private final String method;
    /**
     * 请求目标端点。
     *
     * <p>取值：绝对 URI，必填且不可为 null；是否在传输白名单内由传输层再次校验。
     */
    private final URI endpoint;
    /**
     * 请求头，保留插入顺序、忽略大小写的唯一性已校验。
     *
     * <p>取值：不可修改的有序映射，可为空映射但不可为 null；头名必须匹配
     * {@code [!#$%&'*+.^_`|~0-9A-Za-z-]+}，头值不得含 CR/LF；不得出现 host、content-length、
     * connection、transfer-encoding 这些由传输层管理的头；无默认值。
     */
    private final Map<String, String> headers;
    /**
     * 请求体字节，构造时已拷贝一份。
     *
     * <p>取值：非 null 数组；GET/DELETE 必须为空数组，POST 允许为空数组；无默认值，构造时传入 null 视为
     * 空数组。请通过 {@link #getBody()} 读取，它每次返回新副本。
     */
    private final byte[] body;

    /**
     * 创建并校验一次 HTTP 请求。
     *
     * @param method   HTTP 方法，不区分大小写，仅允许 GET/POST/DELETE，不可为 null
     * @param endpoint 目标端点，不可为 null
     * @param headers  请求头，可为 null（视为不带头）；合法字符、重复头与传输层管理的头都会被校验
     * @param body     请求体，可为 null（视为空数组）；GET/DELETE 传入非空字节会被拒绝
     * @throws IllegalArgumentException 方法为 null 或不受支持、端点为 null、GET/DELETE 带请求体、
     *                                  头名或头值非法、同名头重复、占用了传输层管理的头时
     */
    public RawHttpRequest(String method, URI endpoint, Map<String, String> headers, byte[] body) {
        if (method == null) {
            throw new IllegalArgumentException("method must not be null");
        }
        String normalizedMethod = method.toUpperCase(Locale.ROOT);
        if (!"GET".equals(normalizedMethod) && !"POST".equals(normalizedMethod) && !"DELETE".equals(normalizedMethod)) {
            throw new IllegalArgumentException("unsupported HTTP method");
        }
        if (endpoint == null) {
            throw new IllegalArgumentException("endpoint must not be null");
        }
        byte[] safeBody = body == null ? new byte[0] : body;
        if (("GET".equals(normalizedMethod) || "DELETE".equals(normalizedMethod)) && safeBody.length != 0) {
            throw new IllegalArgumentException("method must not contain a body");
        }
        LinkedHashMap<String, String> safeHeaders = new LinkedHashMap<>();
        Set<String> headerNames = new HashSet<>();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                requireHeader(entry.getKey(), entry.getValue());
                String lower = entry.getKey().toLowerCase(Locale.ROOT);

                if (!headerNames.add(lower)) {
                    throw new IllegalArgumentException("duplicate header name");
                }
                if ("host".equals(lower) || "content-length".equals(lower) || "connection".equals(lower) || "transfer-encoding".equals(lower)) {
                    throw new IllegalArgumentException("transport-controlled header");
                }

                safeHeaders.put(entry.getKey(), entry.getValue());
            }
        }
        this.method = normalizedMethod;
        this.endpoint = endpoint;
        this.headers = Collections.unmodifiableMap(safeHeaders);
        this.body = safeBody.clone();
    }


    /**
     * 读取请求体副本。
     *
     * <p>每次调用都返回新的字节数组副本，调用方修改返回值不会影响本对象，可安全用于日志或重放。
     *
     * @return 请求体字节副本，非 null；无请求体时为空数组
     */
    public byte[] getBody() {
        return body.clone();
    }

    /**
     * 校验单个请求头的名字与取值。
     *
     * @param name  头名，必须匹配 RFC 7230 的 token 字符集
     * @param value 头值，不得包含 CR 或 LF
     * @throws IllegalArgumentException 头名为 null 或含非法字符、头值为 null 或含换行符时
     */
    private static void requireHeader(String name, String value) {
        if (name == null || !name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
            throw new IllegalArgumentException("invalid header name");
        }
        if (value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("invalid header value");
        }
    }
}
