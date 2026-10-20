package com.clara.utils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * HTTP 端点解析与校验工具。
 */
public final class HttpUrisUtils {

    private HttpUrisUtils(){

    }
    /**
     * 校验端点是否为安全的绝对 HTTP(S) 地址。
     *
     * @param endpoint 待校验端点，不可为 null
     * @param allowHttp 是否允许明文 HTTP
     * @return 归一化后的端点，非 null
     * @throws IllegalArgumentException 端点非法、含用户信息或片段、协议不被允许时
     */
    public static URI requireAbsolute(URI endpoint, boolean allowHttp) {
        if (endpoint == null || endpoint.getScheme() == null || endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("endpoint is invalid");
        }
        String scheme = endpoint.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !(allowHttp && "http".equals(scheme))) {
            throw new IllegalArgumentException("endpoint scheme is not allowed");
        }
        return endpoint.normalize();
    }

    /**
     * 计算端点的源站三元组 {@code scheme://host:port}。
     *
     * @param endpoint 待计算端点，不可为 null
     * @param allowHttp 是否允许明文 HTTP
     * @return 归一化源站，非 null
     * @throws IllegalArgumentException 端点非法时
     */
    public static String origin(URI endpoint, boolean allowHttp) {
        URI safe = requireAbsolute(endpoint, allowHttp);
        String scheme = safe.getScheme().toLowerCase(Locale.ROOT);
        String host = safe.getHost().toLowerCase(Locale.ROOT);
        int port = safe.getPort();
        int effectivePort = port;
        if (port < 0) {
            effectivePort = "https".equals(scheme) ? 443 : 80;
        }
        String authorityHost = host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
        return scheme + "://" + authorityHost + ":" + effectivePort;
    }

    /**
     * 把配置中的源站写法归一化为 {@code scheme://host:port}。
     *
     * @param value 配置值，不可为空白
     * @param allowHttp 是否允许明文 HTTP
     * @return 归一化源站，非 null
     * @throws IllegalArgumentException 配置值非法或包含路径、查询、片段时
     */
    public static String normalizeOrigin(String value, boolean allowHttp) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("origin must not be blank");
        }
        try {
            URI uri = new URI(value);
            if (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath()) || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("allowed origin must not contain path, query, or fragment");
            }
            return origin(uri, allowHttp);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("invalid allowed origin", exception);
        }
    }
}
