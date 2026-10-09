package com.clara.mcp.client;

import com.clara.exception.ErrorCategory;
import com.clara.exception.ErrorCodes;
import com.clara.mcp.registry.McpErrors;
import com.clara.mcp.registry.McpServerRegistration;
import com.clara.mcp.registry.McpTransportProfile;
import com.clara.mcp.client.DefaultMcpClient;
import com.clara.mcp.client.McpClient;
import com.clara.mcp.transport.McpJsonCodec;
import com.clara.mcp.transport.http.McpHeadersProvider;
import com.clara.mcp.transport.http.StreamableHttpMcpTransport;
import com.clara.mcp.transport.stdio.ProcessFactory;
import com.clara.mcp.transport.stdio.StdioMcpTransport;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按服务端注册项的传输方式创建 {@link DefaultMcpClient} 的客户端工厂默认实现。
 *
 * <p>是什么、在哪一层：{@code clara-mcp} 注册层对 {@link McpClientFactory} 的实现，被
 * {@code DefaultMcpDiscoveryService}（发现）与 {@code DefaultMcpGateway}（调用）使用，使它们不感知传输
 * 方式与连接细节。
 *
 * <p>分派规则：注册项为 {@link McpTransportProfile#STDIO} 时，把 {@code address} 拆分为命令行并以
 * {@link StdioMcpTransport} 承载；为 {@link McpTransportProfile#STREAMABLE_HTTP} 时，把 {@code address}
 * 作为端点并以 {@link StreamableHttpMcpTransport} 承载；其余（如 {@code LEGACY_SSE}）抛领域错误。每次
 * 调用返回一个新的客户端，由调用方负责关闭。
 *
 * <p>注意：stdio 的命令行按空白拆分（首次出现的可执行文件放入允许列表校验），因此可执行文件路径不应含
 * 空格；http 端点只允许已注册地址自身的主机，默认只接受 HTTPS。
 */
public final class DefaultMcpClientFactory implements McpClientFactory {
    private final McpJsonCodec codec;
    private final ProcessFactory processFactory;
    private final List<Path> allowedExecutables;
    private final Path workingDirectory;
    private final McpHeadersProvider headersProvider;
    private final long requestTimeoutMillis;

    /**
     * 创建客户端工厂。
     *
     * @param codec                传输使用的 JSON 编解码器，不可为 null
     * @param processFactory       stdio 传输启动子进程的工厂，可为 null（使用默认实现）
     * @param allowedExecutables   stdio 允许执行的可执行文件绝对路径列表，不可为 null 或空
     * @param workingDirectory     stdio 子进程的工作目录，不可为 null
     * @param headersProvider      http 传输提供的鉴权请求头，可为 null
     * @param requestTimeoutMillis 客户端单次请求超时毫秒数，必须为正
     */
    public DefaultMcpClientFactory(McpJsonCodec codec, ProcessFactory processFactory,
            List<Path> allowedExecutables, Path workingDirectory, McpHeadersProvider headersProvider,
            long requestTimeoutMillis) {
        if (codec == null) { throw new IllegalArgumentException("codec must not be null"); }
        if (allowedExecutables == null || allowedExecutables.isEmpty() || allowedExecutables.contains(null)) {
            throw new IllegalArgumentException("allowedExecutables must not be empty");
        }
        if (workingDirectory == null) { throw new IllegalArgumentException("workingDirectory must not be null"); }
        if (requestTimeoutMillis <= 0L) {
            throw new IllegalArgumentException("requestTimeoutMillis must be positive");
        }
        this.codec = codec;
        this.processFactory = processFactory;
        this.allowedExecutables = Collections.unmodifiableList(new ArrayList<Path>(allowedExecutables));
        this.workingDirectory = workingDirectory;
        this.headersProvider = headersProvider;
        this.requestTimeoutMillis = requestTimeoutMillis;
    }

    public McpClient create(McpServerRegistration registration) {
        if (registration == null) { throw new IllegalArgumentException("registration must not be null"); }
        McpTransportProfile profile = registration.getTransportProfile();
        if (profile == McpTransportProfile.STDIO) {
            return new DefaultMcpClient(stdio(registration), requestTimeoutMillis);
        }
        if (profile == McpTransportProfile.STREAMABLE_HTTP) {
            return new DefaultMcpClient(http(registration), requestTimeoutMillis);
        }
        throw McpErrors.error(ErrorCodes.CAPABILITY_NOT_SUPPORTED, ErrorCategory.CAPABILITY);
    }

    private StdioMcpTransport stdio(McpServerRegistration registration) {
        List<String> command = registration.getCommand().isEmpty()
                ? split(registration.getAddress()) : registration.getCommand();
        StdioMcpTransport.Builder builder = StdioMcpTransport.builder()
                .command(command)
                .allowedExecutables(allowedExecutables)
                .workingDirectory(workingDirectory)
                .codec(codec);
        if (!registration.getEnv().isEmpty()) { builder.environment(registration.getEnv()); }
        if (processFactory != null) { builder.processFactory(processFactory); }
        return builder.build();
    }

    private StreamableHttpMcpTransport http(McpServerRegistration registration) {
        URI endpoint = URI.create(registration.getAddress());
        Set<String> allowedHosts = new LinkedHashSet<String>();
        allowedHosts.add(endpoint.getHost());
        StreamableHttpMcpTransport.Builder builder = StreamableHttpMcpTransport.builder()
                .endpoint(endpoint)
                .allowedHosts(allowedHosts)
                .codec(codec);
        McpHeadersProvider provider = registration.getHeaders().isEmpty()
                ? headersProvider : fixedHeaders(registration.getHeaders());
        if (provider != null) { builder.headersProvider(provider); }
        return builder.build();
    }

    private static McpHeadersProvider fixedHeaders(final Map<String, String> headers) {
        return new McpHeadersProvider() {
            public Map<String, String> getHeaders() { return headers; }
        };
    }

    private static List<String> split(String address) {
        String[] tokens = address == null ? new String[0] : address.trim().split("\\s+");
        List<String> command = new ArrayList<String>();
        for (String token : tokens) {
            if (!token.isEmpty()) { command.add(token); }
        }
        if (command.isEmpty()) {
            throw McpErrors.error(ErrorCodes.MCP_INVALID_MESSAGE, ErrorCategory.VALIDATION);
        }
        return command;
    }
}
