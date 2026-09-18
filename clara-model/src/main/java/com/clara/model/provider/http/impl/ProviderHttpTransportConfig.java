package com.clara.model.provider.http.impl;

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
 * <p>除源白名单、超时与报文上限外，还支持一组平台级默认请求头：它们在发送时先写入连接，再由
 * 适配器自己拼装的请求头覆盖同名项，因此适合补充网关需要的追踪、路由或产品标识类请求头。
 */
@Data
public final class ProviderHttpTransportConfig {
    /**
     * 请求头名的合法字符集。
     */
    private static final String HEADER_NAME = "[!#$%&'*+.^_`|~0-9A-Za-z-]+";
    /**
     * 由传输层自行管理的请求头，配置中不允许出现。
     */
    private static final Set<String> TRANSPORT_CONTROLLED_HEADERS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("host", "content-length", "connection", "transfer-encoding")));

    private final Set<String> allowedOrigins;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final int maxRequestBytes;
    private final int maxResponseBytes;
    private final boolean allowHttp;
    /**
     * 平台级默认请求头；同名请求头以适配器为准，因此只用于补充。
     */
    private final Map<String, String> headers;

    public ProviderHttpTransportConfig(Set<String> allowedOrigins, int connectTimeoutMillis, int readTimeoutMillis, int maxRequestBytes, int maxResponseBytes, boolean allowHttp) {
        this(allowedOrigins, connectTimeoutMillis, readTimeoutMillis, maxRequestBytes, maxResponseBytes, allowHttp, Collections.<String, String>emptyMap());
    }

    public ProviderHttpTransportConfig(Set<String> allowedOrigins, int connectTimeoutMillis, int readTimeoutMillis, int maxRequestBytes, int maxResponseBytes, boolean allowHttp, Map<String, String> headers) {
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
     * <p>校验规则与 {@link ProviderHttpRequest} 保持一致：头名必须是合法 token、头值不得包含 CR/LF、
     * 同名头（忽略大小写）不得重复、不得占用传输层自行管理的头。这些头只用于补充，适配器已设置的
     * 同名头不会被覆盖。
     *
     * @param headers 配置中声明的请求头，可为 null
     * @return 不可变的请求头映射
     * @throws IllegalArgumentException 头名或头值非法时
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
