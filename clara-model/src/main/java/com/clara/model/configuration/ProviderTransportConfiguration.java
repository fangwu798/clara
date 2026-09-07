package com.clara.model.configuration;

import com.clara.model.provider.http.Jdk8ProviderHttpTransportConfig;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 描述一个模型平台共用的 HTTP 安全边界和资源限制。
 *
 * <p>常规用法：每个平台 YAML 配置一组精确的 {@code allowedOrigins}、连接/读取超时以及请求/响应
 * 大小上限，再通过 {@link #toHttpTransportConfig()} 创建 JDK 8 HTTP 传输配置。
 *
 * <p>注意：origin 只能包含协议、主机和可选端口，不能包含接口路径。生产环境应保持 HTTPS 且
 * {@code allowHttp=false}；请求参数中的端点仍会由传输层再次对照白名单校验。
 */
public final class ProviderTransportConfiguration {
    private final Set<String> allowedOrigins;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final int maxRequestBytes;
    private final int maxResponseBytes;
    private final boolean allowHttp;

    public ProviderTransportConfiguration(Set<String> allowedOrigins,
            int connectTimeoutMillis, int readTimeoutMillis, int maxRequestBytes,
            int maxResponseBytes, boolean allowHttp) {
        Jdk8ProviderHttpTransportConfig validated = new Jdk8ProviderHttpTransportConfig(
                allowedOrigins, connectTimeoutMillis, readTimeoutMillis,
                maxRequestBytes, maxResponseBytes, allowHttp);
        this.allowedOrigins = Collections.unmodifiableSet(
                new LinkedHashSet<String>(validated.getAllowedOrigins()));
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        this.maxRequestBytes = maxRequestBytes;
        this.maxResponseBytes = maxResponseBytes;
        this.allowHttp = allowHttp;
    }

    public Set<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public int getReadTimeoutMillis() {
        return readTimeoutMillis;
    }

    public int getMaxRequestBytes() {
        return maxRequestBytes;
    }

    public int getMaxResponseBytes() {
        return maxResponseBytes;
    }

    public boolean isAllowHttp() {
        return allowHttp;
    }

    public Jdk8ProviderHttpTransportConfig toHttpTransportConfig() {
        return new Jdk8ProviderHttpTransportConfig(allowedOrigins, connectTimeoutMillis,
                readTimeoutMillis, maxRequestBytes, maxResponseBytes, allowHttp);
    }
}
