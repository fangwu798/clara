package com.clara.kernel.http;

import com.clara.core.transport.HttpHeaders;
import com.clara.core.transport.HttpTransportPolicy;
import com.clara.utils.HttpUrisUtils;
import com.clara.core.transport.sse.SseParserConfig;
import com.clara.transport.http.OriginEndpointPolicy;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 传输配置：定义一次平台 HTTP 往返的连接参数、安全边界与报文上限。
 *
 * <p>是什么与所属层：本类属于 {@code clara-core} 的 HTTP 层（{@code com.clara.kernel.http}），是实现层
 * 不可变值对象，不是模型 API 契约；它按平台 YAML 的 {@code transport} 节点形状声明端点白名单、超时、
 * 报文上限与平台级默认请求头，构造时一次性校验并冻结，供传输实现读取。
 *
 * <p>谁创建谁调用：由配置加载器在装配阶段从平台 YAML 绑定——它是 {@code ProviderConfiguration} 的
 * {@code transport} 字段，绑定走带 {@code @JsonCreator} + {@code @JsonProperty} 的七参构造器
 * （缺了这组注解，所有平台配置都会在加载期失败）；随后由传输实现在每次往返时读取白名单与上限，运行期不再
 * 修改，{@link com.clara.kernel.http.execute.Jdk8HttpTransport} 通过 {@code ConvContext} 取得本对象。
 *
 * <p>使用方法：配置层绑定一次并复用 → 传输层用 {@link #allows(URI)} 做源站校验、用 getter 读取超时、
 * 体积上限与默认请求头。本类没有 close 或释放动作，实例与平台配置同生命周期。
 *
 * <p>除源白名单、超时与报文上限外，还支持一组平台级默认请求头：它们在发送时先写入连接，再由
 * 适配器自己拼装的请求头覆盖同名项，因此适合补充网关需要的追踪、路由或产品标识类请求头。
 *
 * <p>生命周期与线程安全：实例不可变（字段均为 final，白名单与请求头均被冻结为不可修改视图），构造完成
 * 后可安全跨线程共享，多个传输调用可并发读取。
 *
 * <p>失败语义：构造时校验不通过抛 {@link IllegalArgumentException}（白名单为空或含 null、超时或体积
 * 上限非正、origin 非法、请求头非法），属于配置错误，不可重试；构造成功后的读取与 {@link #allows(URI)}
 * 不会抛异常（非法端点直接返回 false）。
 */
@Data
public final class TransportConfig {
    /**
     * 允许访问的源白名单，元素已归一化为 {@code scheme://host:port}。
     *
     * <p>取值：非空的不可修改集合，必填且不得包含 null；每个元素只能包含协议、主机与可选端口，不得带
     * 路径、查询或片段；协议默认只允许 https，{@code allowHttp=true} 时才允许 http；无默认值。
     */
    private final Set<String> allowedOrigins;
    /**
     * 连接超时时间。
     *
     * <p>取值：正数，单位毫秒，必填；实际生效值还会与调用上下文的剩余时间取较小值；无默认值。
     */
    private final int connectTimeoutMillis;
    /**
     * 读取超时时间。
     *
     * <p>取值：正数，单位毫秒，必填；实际生效值还会与调用上下文的剩余时间取较小值；无默认值。
     */
    private final int readTimeoutMillis;
    /**
     * 请求体最大字节数。
     *
     * <p>取值：正数，单位字节，必填；超出时传输层按请求过大错误拒绝；无默认值。
     */
    private final int maxRequestBytes;
    /**
     * 响应体最大字节数。
     *
     * <p>取值：正数，单位字节，必填；超出时传输层按响应超限错误中断；无默认值。SSE 解析器的单事件上限
     * 按约定不应大于该值（本类不校验，由使用方保证）。
     */
    private final int maxResponseBytes;
    /**
     * 是否允许使用明文 HTTP。
     *
     * <p>取值：true 表示 http 与 https 都进入白名单，false 表示只允许 https；无默认值，由调用方显式
     * 传入。生产环境应保持 false。
     */
    private final boolean allowHttp;
    /**
     * 平台级默认请求头；同名请求头以适配器为准，因此只用于补充。
     *
     * <p>取值：不可修改的有序映射，可为空映射但不可为 null（构造时传入 null 视为空映射）；头名必须匹配
     * token 字符集、头值不得含 CR/LF、同名头（忽略大小写）不得重复、不得占用传输层管理的头；
     * 六参构造函数的默认值为空映射。
     */
    private final Map<String, String> headers;



    /**
     * 创建不带平台级默认请求头的传输配置。
     *
     * @param allowedOrigins       源白名单，不可为 null、不可为空、不得含 null
     * @param connectTimeoutMillis 连接超时时间，单位毫秒，必须为正
     * @param readTimeoutMillis    读取超时时间，单位毫秒，必须为正
     * @param maxRequestBytes      请求体最大字节数，必须为正
     * @param maxResponseBytes     响应体最大字节数，必须为正
     * @param allowHttp            是否允许使用明文 HTTP
     * @throws IllegalArgumentException 白名单为空或含 null、任一上限非正、origin 非法时
     */
    public TransportConfig(Set<String> allowedOrigins, int connectTimeoutMillis, int readTimeoutMillis, int maxRequestBytes, int maxResponseBytes, boolean allowHttp) {
        this(allowedOrigins, connectTimeoutMillis, readTimeoutMillis, maxRequestBytes, maxResponseBytes, allowHttp, Collections.emptyMap());
    }

    /**
     * 创建带平台级默认请求头的传输配置，同时声明 YAML 形状（{@code @JsonCreator} + {@code @JsonProperty}）。
     *
     * <p>绑定走本构造器：YAML 缺少 {@code allowedOrigins} / {@code connectTimeoutMillis} /
     * {@code readTimeoutMillis} / {@code maxRequestBytes} / {@code maxResponseBytes} / {@code allowHttp}
     * 任一项时由绑定器报「缺失」，{@code headers} 可省略。
     *
     * @param allowedOrigins       源白名单，不可为 null、不可为空、不得含 null
     * @param connectTimeoutMillis 连接超时时间，单位毫秒，必须为正
     * @param readTimeoutMillis    读取超时时间，单位毫秒，必须为正
     * @param maxRequestBytes      请求体最大字节数，必须为正
     * @param maxResponseBytes     响应体最大字节数，必须为正
     * @param allowHttp            是否允许使用明文 HTTP
     * @param headers              平台级默认请求头，可为 null（视为无默认头）
     * @throws IllegalArgumentException 白名单为空或含 null、任一上限非正、origin 非法，或默认请求头非法时
     */
    @JsonCreator
    public TransportConfig(
            @JsonProperty(value = "allowedOrigins", required = true) Set<String> allowedOrigins,
            @JsonProperty(value = "connectTimeoutMillis", required = true) int connectTimeoutMillis,
            @JsonProperty(value = "readTimeoutMillis", required = true) int readTimeoutMillis,
            @JsonProperty(value = "maxRequestBytes", required = true) int maxRequestBytes,
            @JsonProperty(value = "maxResponseBytes", required = true) int maxResponseBytes,
            @JsonProperty(value = "allowHttp", required = true) boolean allowHttp,
            @JsonProperty("headers") Map<String, String> headers) {
        if (allowedOrigins == null || allowedOrigins.isEmpty() || allowedOrigins.contains(null)) {
            throw new IllegalArgumentException("allowedOrigins must not be empty");
        }
        if (connectTimeoutMillis <= 0 || readTimeoutMillis <= 0 || maxRequestBytes <= 0 || maxResponseBytes <= 0) {
            throw new IllegalArgumentException("transport limits must be positive");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : allowedOrigins) {
            normalized.add(HttpUrisUtils.normalizeOrigin(value, allowHttp));
        }
        this.allowedOrigins = Collections.unmodifiableSet(normalized);
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        this.maxRequestBytes = maxRequestBytes;
        this.maxResponseBytes = maxResponseBytes;
        this.allowHttp = allowHttp;
        this.headers = HttpHeaders.validate(headers);
    }


    /**
     * 判断端点是否落在源白名单内。
     *
     * <p>只比较「协议 + 主机 + 端口」三元组（端口缺省时按协议补 443/80），不比较路径、查询与片段；不做
     * DNS 解析，也不跟随重定向。端点含用户信息、协议不被允许或本身非法时返回 false，不抛异常。
     *
     * @param endpoint 待校验端点，可为 null（返回 false）
     * @return 端点源站在白名单内时返回 true，否则返回 false
     */
    public boolean allows(URI endpoint) {
        try {
            return allowedOrigins.contains(HttpUrisUtils.origin(endpoint, allowHttp));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * 单个 SSE 事件与单行的体积上限：平台的 {@code response.completed} 会把完整系统指令、思维链与
     * 最终回答放进一条 {@code data:} 行，64KB 的单行上限会把正常响应误判成体积超限。
     */
    private static final int MAX_SSE_EVENT_BYTES = 8 * 1024 * 1024;

    /**
     * 转换为通用传输策略，供 {@code IHttpTransport} 使用。
     *
     * <p>SSE 的单行、单事件与总字节上限都受 {@code maxResponseBytes} 约束，避免流式响应突破配置上限。
     * 单行与单事件取同一上限：终端事件是一条可能包含完整系统指令与思维链的长行，不能按普通分片的大小限制。
     *
     * @return 通用传输策略，非 null
     */
    public HttpTransportPolicy toHttpTransportPolicy() {
        int maxTotal = maxResponseBytes;
        int maxEvent = Math.min(MAX_SSE_EVENT_BYTES, maxTotal);
        int maxLine = maxEvent;
        SseParserConfig sse = new SseParserConfig(maxLine, maxEvent,
                SseParserConfig.DEFAULT_MAX_EVENTS, maxTotal, false);
        return new HttpTransportPolicy(new OriginEndpointPolicy(allowedOrigins, allowHttp),
                connectTimeoutMillis, readTimeoutMillis, maxRequestBytes, maxResponseBytes,
                headers, sse);
    }
}
