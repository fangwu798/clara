package com.clara.kernel.http;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 定义JDK 8服务提供商HTTP传输器的配置。
 *
 * <p>是什么与所属层：本类属于 {@code clara-model} 的公共 HTTP 层（{@code com.clara.model.http}），是
 * 实现层不可变值对象，不是模型 API 契约；它承载端点白名单、超时、报文上限与平台级默认请求头，构造时
 * 一次性校验并冻结，供传输实现读取。
 *
 * <p>谁创建谁调用：由配置层（{@link com.clara.model.configuration.ProviderTransportConfiguration}）或
 * 宿主应用创建，再传给传输实现的构造函数（如 {@code impl.Jdk8HttpTransport}）；传输实现在每次往返时读取
 * 白名单与上限，无需在运行期修改本对象。
 *
 * <p>使用方法：装配阶段构造一次并复用 → 注入传输实现 → 传输层用 {@link #allows(URI)} 做源站校验、用
 * getter 读取超时与体积上限和默认请求头。本类没有 close 或释放动作，实例与传输器同生命周期。
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
     * 请求头名的合法字符集。
     */
    private static final String HEADER_NAME = "[!#$%&'*+.^_`|~0-9A-Za-z-]+";
    /**
     * 由传输层自行管理的请求头，配置中不允许出现。
     */
    private static final Set<String> TRANSPORT_CONTROLLED_HEADERS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("host", "content-length", "connection", "transfer-encoding")));

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
     * <p>取值：正数，单位字节，必填；超出时传输层按响应超限错误中断；无默认值。SSE 的单事件上限必须不
     * 大于该值。
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
        this(allowedOrigins, connectTimeoutMillis, readTimeoutMillis, maxRequestBytes, maxResponseBytes, allowHttp, Collections.<String, String>emptyMap());
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
        LinkedHashSet<String> normalized = new LinkedHashSet<String>();
        for (String value : allowedOrigins) {
            normalized.add(normalizeConfiguredOrigin(value, allowHttp));
        }
        this.allowedOrigins = Collections.unmodifiableSet(normalized);
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        this.maxRequestBytes = maxRequestBytes;
        this.maxResponseBytes = maxResponseBytes;
        this.allowHttp = allowHttp;
        this.headers = validateHeaders(headers);
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
            return allowedOrigins.contains(origin(endpoint, allowHttp));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * 校验并冻结平台级默认请求头。
     *
     * <p>校验规则与 {@link RawHttpRequest} 保持一致：头名必须是合法 token、头值不得包含 CR/LF、
     * 同名头（忽略大小写）不得重复、不得占用传输层自行管理的头。这些头只用于补充，适配器已设置的
     * 同名头不会被覆盖。
     *
     * @param headers 配置中声明的请求头，可为 null
     * @return 不可变的请求头映射，非 null；headers 为 null 或空时返回空映射
     * @throws IllegalArgumentException 头名或头值非法、同名头重复、占用了传输层管理的头时
     */
    private static Map<String, String> validateHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Collections.emptyMap();
        }
        LinkedHashMap<String, String> safe = new LinkedHashMap<String, String>();
        Set<String> names = new HashSet<String>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (name == null || !name.matches(HEADER_NAME)) {
                throw new IllegalArgumentException("invalid configured header name");
            }
            if (value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("invalid configured header value");
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (!names.add(lower)) {
                throw new IllegalArgumentException("duplicate configured header name");
            }
            if (TRANSPORT_CONTROLLED_HEADERS.contains(lower)) {
                throw new IllegalArgumentException("transport-controlled header must not be configured");
            }
            safe.put(name, value);
        }
        return Collections.unmodifiableMap(safe);
    }

    /**
     * 归一化配置中的源站写法。
     *
     * @param value     配置值，不得为空白
     * @param allowHttp 是否允许明文 HTTP
     * @return 归一化后的 {@code scheme://host:port}
     * @throws IllegalArgumentException 值为空白、URI 语法非法、含路径/查询/片段或协议不被允许时
     */
    private static String normalizeConfiguredOrigin(String value, boolean allowHttp) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("origin must not be blank");
        }
        try {
            URI uri = new URI(value);
            if (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath())
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("allowed origin must not contain path, query, or fragment");
            }
            return origin(uri, allowHttp);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("invalid allowed origin", exception);
        }
    }

    /**
     * 把 URI 归一化为源站三元组。
     *
     * @param uri       待归一化的 URI
     * @param allowHttp 是否允许明文 HTTP
     * @return {@code scheme://host:port}，端口缺省时按协议补 443 或 80
     * @throws IllegalArgumentException 协议或主机缺失、含用户信息、协议不被允许时
     */
    private static String origin(URI uri, boolean allowHttp) {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("endpoint must have a safe origin");
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        host = host.toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !(allowHttp && "http".equals(scheme))) {
            throw new IllegalArgumentException("endpoint scheme is not allowed");
        }
        int port = uri.getPort();
        int effectivePort = port < 0 ? ("https".equals(scheme) ? 443 : 80) : port;
        String authorityHost = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return scheme + "://" + authorityHost + ":" + effectivePort;
    }
}
