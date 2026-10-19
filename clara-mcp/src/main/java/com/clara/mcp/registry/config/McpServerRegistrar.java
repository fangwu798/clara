package com.clara.mcp.registry.config;

import com.clara.mcp.api.IMcpServerRegistry;
import com.clara.mcp.registry.McpRegistrationStatus;
import com.clara.mcp.registry.McpServerRegistration;
import com.clara.mcp.registry.McpTransportProfile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 把加载出的 {@link McpServerConfig} 转换为 {@link McpServerRegistration} 并注册进 {@link IMcpServerRegistry}。
 *
 * <p>是什么、在哪一层：{@code clara-mcp} 注册层的装配入口，衔接「配置加载」与「服务端注册」。
 *
 * <p>使用范围：宿主（如 clara-application 装配层）读取配置后，用本类把配置批量登记到注册表；租户标识由调用方提供。
 *
 * <p>转换规则：{@code McpServerConfig} 的 {@code serverKey} / {@code transportProfile} / {@code command} /
 * {@code env} / {@code headers} 原样落到注册项；注册项的兼容字段 {@code address} 由 stdio 的命令行拼接或 http
 * 的 {@code url} 生成；注册状态置为 {@link McpRegistrationStatus#ACTIVE}。
 */
public final class McpServerRegistrar {
    private final IMcpServerRegistry registry;

    /**
     * 创建注册器。
     *
     * @param registry 目标服务端注册表，不可为 null
     */
    public McpServerRegistrar(IMcpServerRegistry registry) {
        if (registry == null) { throw new IllegalArgumentException("registry must not be null"); }
        this.registry = registry;
    }

    /**
     * 批量注册配置。
     *
     * @param tenantId 租户标识，不可为空白
     * @param configs  待注册配置，不可为 null，元素不可为 null
     * @return 已注册的注册项列表（与入参顺序一致），非 null
     */
    public List<McpServerRegistration> registerAll(String tenantId, List<McpServerConfig> configs) {
        if (configs == null) { throw new IllegalArgumentException("configs must not be null"); }
        List<McpServerRegistration> registered = new ArrayList<>(configs.size());
        for (McpServerConfig config : configs) {
            McpServerRegistration registration = toRegistration(tenantId, config);
            registry.register(registration);
            registered.add(registration);
        }
        return Collections.unmodifiableList(registered);
    }

    /**
     * 把单个配置转换为注册项。
     *
     * @param tenantId 租户标识，不可为空白
     * @param config   服务端配置，不可为 null
     * @return 注册项，非 null
     */
    public McpServerRegistration toRegistration(String tenantId, McpServerConfig config) {
        if (config == null) { throw new IllegalArgumentException("config must not be null"); }
        String address = config.getTransportProfile() == McpTransportProfile.STDIO
                ? String.join(" ", config.getCommand()) : config.getUrl();
        return new McpServerRegistration(tenantId, config.getServerKey(), config.getTransportProfile(),
                address, null, null, null, McpRegistrationStatus.ACTIVE,
                config.getCommand(), config.getEnv(), config.getHeaders());
    }
}
