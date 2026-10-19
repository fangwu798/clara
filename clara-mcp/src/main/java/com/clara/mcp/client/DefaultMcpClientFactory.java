package com.clara.mcp.client;

import com.clara.enums.ErrorCategoryEnum;
import com.clara.exception.ErrorCodes;
import com.clara.mcp.McpErrors;
import com.clara.mcp.api.IMcpClient;
import com.clara.mcp.api.IMcpClientFactory;
import com.clara.mcp.api.IMcpHeadersProvider;
import com.clara.mcp.api.IMcpJsonCodec;
import com.clara.mcp.api.IProcessFactory;
import com.clara.mcp.registry.McpServerRegistration;
import com.clara.mcp.registry.McpTransportProfile;
import com.clara.mcp.transport.http.StreamableHttpMcpTransport;
import com.clara.mcp.transport.stdio.StdioMcpTransport;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按服务端注册项的传输方式创建 {@link DefaultMcpClient} 的客户端工厂默认实现。
 *
 * <p>是什么、在哪一层：{@code clara-mcp} 注册层对 {@link IMcpClientFactory} 的实现，被
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
public final class DefaultMcpClientFactory implements IMcpClientFactory {
    private final IMcpJsonCodec codec;
    private final IProcessFactory processFactory;
    private final List<Path> allowedExecutables;
    private final Path workingDirectory;
    private final IMcpHeadersProvider headersProvider;
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
    public DefaultMcpClientFactory(IMcpJsonCodec codec, IProcessFactory processFactory,
                                   List<Path> allowedExecutables, Path workingDirectory, IMcpHeadersProvider headersProvider,
                                   long requestTimeoutMillis) {
        if (codec == null) {
            throw new IllegalArgumentException("codec must not be null");
        }
        if (allowedExecutables == null || allowedExecutables.isEmpty() || allowedExecutables.contains(null)) {
            throw new IllegalArgumentException("allowedExecutables must not be empty");
        }
        if (workingDirectory == null) {
            throw new IllegalArgumentException("workingDirectory must not be null");
        }
        if (requestTimeoutMillis <= 0L) {
            throw new IllegalArgumentException("requestTimeoutMillis must be positive");
        }
        this.codec = codec;
        this.processFactory = processFactory;
        this.allowedExecutables = Collections.unmodifiableList(new ArrayList<>(allowedExecutables));
        this.workingDirectory = workingDirectory;
        this.headersProvider = headersProvider;
        this.requestTimeoutMillis = requestTimeoutMillis;
    }

    public IMcpClient create(McpServerRegistration registration) {
        if (registration == null) {
            throw new IllegalArgumentException("registration must not be null");
        }
        McpTransportProfile profile = registration.getTransportProfile();
        if (profile == McpTransportProfile.STDIO) {
            return new DefaultMcpClient(stdio(registration), requestTimeoutMillis);
        }
        if (profile == McpTransportProfile.STREAMABLE_HTTP) {
            return new DefaultMcpClient(http(registration), requestTimeoutMillis);
        }
        throw McpErrors.error(ErrorCodes.CAPABILITY_NOT_SUPPORTED, ErrorCategoryEnum.CAPABILITY);
    }

    private StdioMcpTransport stdio(McpServerRegistration registration) {
        List<String> command = registration.getCommand().isEmpty() ? split(registration.getAddress()) : registration.getCommand();
        command = absolutize(command);
        StdioMcpTransport.Builder builder = StdioMcpTransport.builder()
                .command(command)
                .allowedExecutables(allowedExecutables)
                .workingDirectory(workingDirectory)
                .codec(codec);
        if (!registration.getEnv().isEmpty()) {
            builder.environment(registration.getEnv());
        }
        if (processFactory != null) {
            builder.processFactory(processFactory);
        }
        return builder.build();
    }

    /**
     * 把命令首项绝对化：MCP 生态的配置惯例是写 {@code npx} / {@code node} 这类裸命令名，而 stdio 传输只接受
     * 绝对路径，直接用原始配置会在建连前就因「executable must be absolute」失败。
     *
     * <p>解析办法是在允许清单里按文件名匹配（清单由宿主按 PATH / PATHEXT 展开）：既不要求在配置里写死绝对
     * 路径，也不放宽「必须命中允许清单」这条安全前提——匹配不上就原样返回，由传输层照旧报「必须是绝对路径」。
     *
     * @param command 原始命令，不可为空
     * @return 首项已绝对化的命令列表；无需或无法绝对化时返回原列表
     */
    private List<String> absolutize(List<String> command) {
        if (command.isEmpty() || Paths.get(command.get(0)).isAbsolute()) {
            return command;
        }
        String head = command.get(0);
        for (Path candidate : allowedExecutables) {
            Path fileNamePath = candidate.getFileName();
            if (fileNamePath == null) {
                continue;
            }
            String fileName = fileNamePath.toString();
            int dot = fileName.lastIndexOf('.');
            String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
            if (fileName.equalsIgnoreCase(head) || stem.equalsIgnoreCase(head)) {
                List<String> resolved = new ArrayList<>(command);
                resolved.set(0, candidate.toString());
                return resolved;
            }
        }
        return command;
    }

    private StreamableHttpMcpTransport http(McpServerRegistration registration) {
        URI endpoint = URI.create(registration.getAddress());
        Set<String> allowedHosts = new LinkedHashSet<>();
        allowedHosts.add(endpoint.getHost());
        StreamableHttpMcpTransport.Builder builder = StreamableHttpMcpTransport.builder()
                .endpoint(endpoint)
                .allowedHosts(allowedHosts)
                .codec(codec);
        IMcpHeadersProvider provider = registration.getHeaders().isEmpty()
                ? headersProvider : fixedHeaders(registration.getHeaders());
        if (provider != null) {
            builder.headersProvider(provider);
        }
        return builder.build();
    }

    private static IMcpHeadersProvider fixedHeaders(final Map<String, String> headers) {
        return () -> headers;
    }

    private static List<String> split(String address) {
        String[] tokens = address == null ? new String[0] : address.trim().split("\\s+");
        List<String> command = new ArrayList<>();
        for (String token : tokens) {
            if (!token.isEmpty()) {
                command.add(token);
            }
        }
        if (command.isEmpty()) {
            throw McpErrors.error(ErrorCodes.MCP_INVALID_MESSAGE, ErrorCategoryEnum.VALIDATION);
        }
        return command;
    }
}
