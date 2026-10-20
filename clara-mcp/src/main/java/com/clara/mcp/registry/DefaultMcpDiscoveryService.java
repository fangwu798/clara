package com.clara.mcp.registry;

import com.clara.exception.ClaraException;
import com.clara.enums.ErrorCategoryEnum;
import com.clara.exception.ErrorCodes;
import com.clara.mcp.McpErrors;
import com.clara.mcp.api.IMcpCapabilityCatalog;
import com.clara.mcp.api.IMcpClient;
import com.clara.mcp.api.IMcpClientFactory;
import com.clara.mcp.api.IMcpDiscoveryService;
import com.clara.mcp.api.IMcpServerRegistry;
import com.clara.mcp.client.McpInitialization;
import com.clara.mcp.client.McpPage;
import com.clara.mcp.client.McpServerIdentity;
import com.clara.mcp.registry.capability.McpPromptDescriptor;
import com.clara.mcp.registry.capability.McpResourceDescriptor;
import com.clara.mcp.registry.capability.McpResourceTemplateDescriptor;
import com.clara.mcp.registry.capability.McpToolDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 初始化 MCP 服务端，分页发现工具、资源和提示词，并发布带协议版本及身份的能力快照。
 *
 * <p>常规用法：注册服务端后调用 {@link #refresh} 完成发现、发布目录并将注册状态更新为 ACTIVE；
 * 仅需探测而不发布时可调用 {@link #discover}。
 *
 * <p>注意：客户端在每次发现后都会关闭。协议版本必须位于支持列表，已审批的服务端身份发生变化会将注册项
 * 标记为 REVIEW_REQUIRED；分页页数和游标去重均受保护，不能依赖无限分页或重复游标。
 */
public final class DefaultMcpDiscoveryService implements IMcpDiscoveryService {
    private final IMcpServerRegistry registry;
    private final IMcpCapabilityCatalog catalog;
    private final IMcpClientFactory clientFactory;
    private final List<String> supportedVersions;
    private final int maxPages;
    private final Supplier<String> snapshotIds;
    private final LongSupplier clock;

    public DefaultMcpDiscoveryService(IMcpServerRegistry registry, IMcpCapabilityCatalog catalog, IMcpClientFactory clientFactory, List<String> supportedVersions, int maxPages) {
        this(registry, catalog, clientFactory, supportedVersions, maxPages, () -> UUID.randomUUID().toString(), System::currentTimeMillis);
    }

    DefaultMcpDiscoveryService(IMcpServerRegistry registry, IMcpCapabilityCatalog catalog, IMcpClientFactory clientFactory, List<String> supportedVersions, int maxPages, Supplier<String> snapshotIds, LongSupplier clock) {
        if (registry == null || catalog == null || clientFactory == null || snapshotIds == null || clock == null) {
            throw new IllegalArgumentException("dependencies must not be null");
        }
        if (supportedVersions == null || supportedVersions.isEmpty() || supportedVersions.contains(null)) {
            throw new IllegalArgumentException("supportedVersions must not be empty");
        }
        if (maxPages <= 0) {
            throw new IllegalArgumentException("maxPages must be positive");
        }
        this.registry = registry;
        this.catalog = catalog;
        this.clientFactory = clientFactory;
        this.supportedVersions = Collections.unmodifiableList(new ArrayList<>(supportedVersions));
        this.maxPages = maxPages;
        this.snapshotIds = snapshotIds;
        this.clock = clock;
    }

    public McpServerSnapshot discover(final McpServerRegistration registration) {
        if (registration == null) {
            throw new IllegalArgumentException("registration must not be null");
        }
        IMcpClient client = clientFactory.create(registration);
        McpServerSnapshot snapshot;
        try {
            McpInitialization initialization = client.initialize(supportedVersions);
            validateInitialization(registration, initialization);
            List<McpToolDescriptor> tools = initialization.getCapabilities().supportsTools() ? collect(IMcpClient::listTools, client) : Collections.emptyList();
            List<McpResourceDescriptor> resources = initialization.getCapabilities().supportsResources() ? collect(IMcpClient::listResources, client) : Collections.emptyList();
            List<McpResourceTemplateDescriptor> resourceTemplates = initialization.getCapabilities().supportsResources() ? collect(IMcpClient::listResourceTemplates, client) : Collections.emptyList();
            List<McpPromptDescriptor> prompts = initialization.getCapabilities().supportsPrompts() ? collect(IMcpClient::listPrompts, client) : Collections.emptyList();
            snapshot = new McpServerSnapshot(
                    snapshotIds.get(),
                    registration.getServerKey(),
                    clock.getAsLong(),
                    initialization.getProtocolVersion(),
                    initialization.getIdentity(),
                    initialization.getCapabilities(),
                    tools, resources,
                    resourceTemplates,
                    prompts,
                    McpHealthStatus.HEALTHY);
        } catch (RuntimeException exception) {
            closeQuietly(client);
            throw exception;
        }
        try {
            client.close();
        } catch (IOException exception) {
            throw McpErrors.error(ErrorCodes.MCP_DISCOVERY_FAILED, ErrorCategoryEnum.EXTERNAL_SERVICE);
        }
        return snapshot;
    }

    private static void closeQuietly(IMcpClient client) {
        try {
            client.close();
        } catch (IOException exception) {
            // 主流程已经失败，关闭异常不再覆盖原始原因。
        }
    }

    public void refresh(String serverKey) {
        McpServerRegistration registration = registry.get(serverKey);
        if (registration == null) {
            throw McpErrors.error(ErrorCodes.MCP_SERVER_NOT_FOUND, ErrorCategoryEnum.VALIDATION);
        }
        try {
            McpServerSnapshot snapshot = discover(registration);
            catalog.publish(snapshot);
            registry.updateStatus(serverKey, McpRegistrationStatus.ACTIVE);
        } catch (ClaraException exception) {
            if (ErrorCodes.MCP_PROTOCOL_INCOMPATIBLE.equals(exception.getError().getCode())) {
                registry.updateStatus(serverKey, McpRegistrationStatus.INCOMPATIBLE);
            } else if (ErrorCodes.MCP_SERVER_IDENTITY_CHANGED.equals(exception.getError().getCode())) {
                registry.updateStatus(serverKey, McpRegistrationStatus.REVIEW_REQUIRED);
            }
            throw exception;
        }
    }

    private void validateInitialization(McpServerRegistration registration, McpInitialization initialization) {
        if (initialization == null || !supportedVersions.contains(initialization.getProtocolVersion())) {
            throw McpErrors.error(ErrorCodes.MCP_PROTOCOL_INCOMPATIBLE, ErrorCategoryEnum.CAPABILITY);
        }
        McpServerIdentity identity = initialization.getIdentity();
        if (registration.getApprovedIdentityName() != null && !registration.getApprovedIdentityName().equals(identity.getName())) {
            throw McpErrors.error(ErrorCodes.MCP_SERVER_IDENTITY_CHANGED, ErrorCategoryEnum.AUTHORIZATION);
        }
        if (registration.getApprovedIdentityVersion() != null && !registration.getApprovedIdentityVersion().equals(identity.getVersion())) {
            throw McpErrors.error(ErrorCodes.MCP_SERVER_IDENTITY_CHANGED, ErrorCategoryEnum.AUTHORIZATION);
        }
    }

    private <T> List<T> collect(BiFunction<IMcpClient, String, McpPage<T>> loader, IMcpClient client) {
        List<T> result = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            if (++pages > maxPages) {
                throw McpErrors.error(ErrorCodes.MCP_PAGINATION_LIMIT_EXCEEDED, ErrorCategoryEnum.RESOURCE_LIMIT);
            }
            McpPage<T> page = loader.apply(client, cursor);
            if (page == null) {
                throw McpErrors.error(ErrorCodes.MCP_DISCOVERY_FAILED, ErrorCategoryEnum.EXTERNAL_SERVICE);
            }
            result.addAll(page.getItems());
            cursor = page.getNextCursor();
            if (cursor != null && !cursors.add(cursor)) {
                throw McpErrors.error(ErrorCodes.MCP_PAGINATION_CURSOR_REPEATED, ErrorCategoryEnum.EXTERNAL_SERVICE);
            }
        } while (cursor != null);
        return Collections.unmodifiableList(result);
    }

}
