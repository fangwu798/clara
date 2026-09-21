package com.clara.kernel.http;

import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 封装服务提供商HTTP操作的响应数据。
 *
 * <p>是什么与所属层：本类属于 {@code clara-model} 的公共 HTTP 层（{@code com.clara.model.http}），是
 * 实现层不可变值对象，不是模型 API 契约；它承载传输层读取到的状态码、响应头与响应体，供
 * {@link ModelInvoker} 判定成功与否、供 {@link ICallExchange#decode} 解析成领域对象。
 *
 * <p>谁创建谁调用：只由 {@link IHttpTransport} 的实现创建（如 {@code impl.Jdk8HttpTransport}）；由
 * {@link ModelInvoker} 读取状态码，再由适配器的 {@link ICallExchange} 实现读取响应头与响应体。
 *
 * <p>使用方法：调用
 * {@link IHttpTransport#execute(RawHttpRequest, CallContext)} 或流式重载拿到本对象 →
 * 先用 {@code getStatusCode()} 判断成败（2xx 才有完整业务响应体）→ 再用 {@code getHeaders()}、
 * {@code getBody()} 解析。本类没有 close 或释放动作，也不持有连接与流；{@code getBody()} 每次返回副本。
 *
 * <p>生命周期与线程安全：实例不可变（字段均为 final，响应头被逐层冻结，响应体在构造与读取时分别拷贝），
 * 构造完成后可安全跨线程共享。
 *
 * <p>失败语义：构造时校验不通过抛 {@link IllegalArgumentException}（状态码不在 100~599、响应体为
 * null），属于调用方编程错误；构造时不会因为响应头含 null 而失败——这类条目被静默丢弃。构造成功后的读取
 * 方法不会抛异常，也不会返回 null。
 *
 * <p>注意：流式调用成功时 {@code body} 为空数组，事件已由 {@link com.clara.model.http.sse.ISseListener}
 * 消费；非 2xx 时响应体是错误响应体，本类不做任何错误映射。
 */
@Data
public final class RawHttpResponse {
    /**
     * HTTP 状态码，非 2xx 由调用方决定错误语义。
     *
     * <p>取值：100~599 的整数，无默认值，必填；超出范围构造即失败。
     */
    private final int statusCode;
    /**
     * 响应头，保持服务端返回的顺序，头名大小写按原样保留。
     *
     * <p>取值：不可修改的有序映射，值列表同样不可修改，可为空映射但不可为 null；构造时键或值含 null 的
     * 条目会被丢弃；无默认值。
     */
    private final Map<String, List<String>> headers;
    /**
     * 响应体字节，构造时已拷贝一份。
     *
     * <p>取值：非 null 数组，可为空数组（流式成功时必为空）；无默认值，构造时传入 null 会被拒绝。请通过
     * {@link #getBody()} 读取，它每次返回新副本。
     */
    private final byte[] body;

    /**
     * 创建并校验一次 HTTP 响应。
     *
     * @param statusCode HTTP 状态码，必须落在 100~599
     * @param headers    响应头，可为 null（视为无响应头）；含 null 键或 null 值的条目会被丢弃
     * @param body       响应体，不可为 null（无响应体时传空数组）
     * @throws IllegalArgumentException 状态码不在 100~599 范围内，或响应体为 null 时
     */
    public RawHttpResponse(int statusCode, Map<String, List<String>> headers, byte[] body) {
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("invalid HTTP status code");
        }
        if (body == null) {
            throw new IllegalArgumentException("body must not be null");
        }
        LinkedHashMap<String, List<String>> copied = new LinkedHashMap<String, List<String>>();
        if (headers != null) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || entry.getValue().contains(null)) {
                    continue;
                }
                copied.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
            }
        }
        this.statusCode = statusCode;
        this.headers = Collections.unmodifiableMap(copied);
        this.body = body.clone();
    }


    /**
     * 读取响应体副本。
     *
     * <p>每次调用都返回新的字节数组副本，调用方修改返回值不会影响本对象，可安全用于日志或重复解析。
     *
     * @return 响应体字节副本，非 null；无响应体时为空数组
     */
    public byte[] getBody() {
        return body.clone();
    }
}
