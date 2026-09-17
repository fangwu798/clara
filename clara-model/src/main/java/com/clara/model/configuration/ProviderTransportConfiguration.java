package com.clara.model.configuration;

import com.clara.model.provider.http.ProviderHttpTransportConfig;
import lombok.Data;

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
@Data
public final class ProviderTransportConfiguration {
    /**
     * 允许访问的源白名单，仅含协议、主机和可选端口。
     */
    private final Set<String> allowedOrigins;
    /**
     * 连接超时时间，单位毫秒。
     */
    private final int connectTimeoutMillis;
    /**
     * 读取超时时间，单位毫秒。
     */
    private final int readTimeoutMillis;
    /**
     * 请求体最大字节数。
     */
    private final int maxRequestBytes;
    /**
     * 响应体最大字节数。
     */
    private final int maxResponseBytes;
    /**
     * 是否允许使用明文 HTTP。
     */
    private final boolean allowHttp;

    /**
     * 创建传输配置。
     *
     * @param allowedOrigins       源白名单，不可为空
     * @param connectTimeoutMillis 连接超时时间，单位毫秒
     * @param readTimeoutMillis    读取超时时间，单位毫秒
     * @param maxRequestBytes      请求体最大字节数
     * @param maxResponseBytes     响应体最大字节数
     * @param allowHttp            是否允许使用明文 HTTP
     * @throws IllegalArgumentException 参数不满足底层传输配置校验时
     */
    public ProviderTransportConfiguration(Set<String> allowedOrigins, int connectTimeoutMillis, int readTimeoutMillis, int maxRequestBytes, int maxResponseBytes, boolean allowHttp) {
        ProviderHttpTransportConfig validated = new ProviderHttpTransportConfig(allowedOrigins, connectTimeoutMillis, readTimeoutMillis, maxRequestBytes, maxResponseBytes, allowHttp);
        this.allowedOrigins = Collections.unmodifiableSet(new LinkedHashSet<>(validated.getAllowedOrigins()));
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        this.maxRequestBytes = maxRequestBytes;
        this.maxResponseBytes = maxResponseBytes;
        this.allowHttp = allowHttp;
    }

    /**
     * 转换为 JDK 8 HTTP 传输配置。
     *
     * @return 等价的 JDK 8 传输配置
     */
    public ProviderHttpTransportConfig toHttpTransportConfig() {
        return new ProviderHttpTransportConfig(allowedOrigins, connectTimeoutMillis, readTimeoutMillis, maxRequestBytes, maxResponseBytes, allowHttp);
    }
}
