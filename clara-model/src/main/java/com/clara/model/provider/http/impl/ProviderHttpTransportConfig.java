package com.clara.model.provider.http.impl;

import lombok.Data;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 定义JDK 8服务提供商HTTP传输器的配置。
 */
@Data
public final class ProviderHttpTransportConfig {
    private final Set<String> allowedOrigins;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final int maxRequestBytes;
    private final int maxResponseBytes;
    private final boolean allowHttp;

    public ProviderHttpTransportConfig(Set<String> allowedOrigins,
                                       int connectTimeoutMillis, int readTimeoutMillis, int maxRequestBytes,
                                       int maxResponseBytes, boolean allowHttp) {
        if (allowedOrigins == null || allowedOrigins.isEmpty() || allowedOrigins.contains(null)) {
            throw new IllegalArgumentException("allowedOrigins must not be empty");
        }
        if (connectTimeoutMillis <= 0 || readTimeoutMillis <= 0
                || maxRequestBytes <= 0 || maxResponseBytes <= 0) {
            throw new IllegalArgumentException("transport limits must be positive");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<String>();
        for (String value : allowedOrigins) normalized.add(normalizeConfiguredOrigin(value, allowHttp));
        this.allowedOrigins = Collections.unmodifiableSet(normalized);
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        this.maxRequestBytes = maxRequestBytes;
        this.maxResponseBytes = maxResponseBytes;
        this.allowHttp = allowHttp;
    }


    public boolean allows(URI endpoint) {
        try {
            return allowedOrigins.contains(origin(endpoint, allowHttp));
        } catch (IllegalArgumentException exception) {
            return false;
        }
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
