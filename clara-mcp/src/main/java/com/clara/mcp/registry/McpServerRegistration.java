package com.clara.mcp.registry;

import com.clara.utils.StringUtils;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表示一个 MCP 服务端的注册项：服务端标识、传输方式（含地址 / 鉴权引用 / 结构化连接参数）、
 * 已审批身份与注册状态。
 *
 * <p>不可变值对象 + 构造期校验；{@link #withStatus(McpRegistrationStatus)} 返回带新状态的副本，
 * 供 {@code IMcpServerRegistry.updateStatus} 做无锁替换。
 *
 * <p>结构化连接参数（可选）：stdio 用 {@link #getCommand()}（首个元素为可执行文件，其后为参数）与
 * {@link #getEnv()}；http 用 {@link #getHeaders()} 附加鉴权等请求头。{@code address} 仍保留为兼容字段：
 * stdio 上等价于命令行拼接，http 上等价于端点 URL，供日志 / 展示与回退使用。
 */
@Getter
public final class McpServerRegistration {
    private final String serverKey;
    private final McpTransportProfile transportProfile;
    private final String address;
    private final String authRef;
    private final String approvedIdentityName;
    private final String approvedIdentityVersion;
    private final McpRegistrationStatus status;
    private final List<String> command;
    private final Map<String, String> env;
    private final Map<String, String> headers;

    /**
     * 创建注册项（不含结构化连接参数，向后兼容既有调用方）。
     */
    public McpServerRegistration(String serverKey,
                                 McpTransportProfile transportProfile, String address, String authRef,
                                 String approvedIdentityName, String approvedIdentityVersion,
                                 McpRegistrationStatus status) {
        this(serverKey, transportProfile, address, authRef, approvedIdentityName, approvedIdentityVersion, status, null, null, null);
    }

    /**
     * 创建带结构化连接参数的注册项。
     *
     * @param command stdio 命令行（首个元素为可执行文件，其后为参数），可为 null 或空
     * @param env     stdio 环境变量，可为 null
     * @param headers http 附加请求头，可为 null
     */
    public McpServerRegistration(String serverKey,
                                 McpTransportProfile transportProfile, String address, String authRef,
                                 String approvedIdentityName, String approvedIdentityVersion,
                                 McpRegistrationStatus status, List<String> command, Map<String, String> env,
                                 Map<String, String> headers) {
        if (StringUtils.isBlank(serverKey) || !serverKey.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) {
            throw new IllegalArgumentException("serverKey is invalid");
        }
        if (transportProfile == null) {
            throw new IllegalArgumentException("transportProfile must not be null");
        }
        if (StringUtils.isBlank(address)) {
            throw new IllegalArgumentException("address must not be blank");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        this.serverKey = serverKey;
        this.transportProfile = transportProfile;
        this.address = address;
        this.authRef = authRef;
        this.approvedIdentityName = approvedIdentityName;
        this.approvedIdentityVersion = approvedIdentityVersion;
        this.status = status;
        this.command = immutableCommand(command);
        this.env = immutableMap(env);
        this.headers = immutableMap(headers);
    }

    public boolean isEnabled() {
        return status == McpRegistrationStatus.ACTIVE;
    }

    public McpServerRegistration withStatus(McpRegistrationStatus newStatus) {
        return new McpServerRegistration(serverKey, transportProfile, address, authRef,
                approvedIdentityName, approvedIdentityVersion, newStatus, command, env, headers);
    }

    private static List<String> immutableCommand(List<String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(source));
    }

    private static Map<String, String> immutableMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
