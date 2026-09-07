package com.clara.mcp.registry;

import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCategory;
import com.clara.kernel.ErrorCodes;
import com.clara.mcp.client.McpClient;
import com.clara.mcp.client.McpInitialization;
import com.clara.mcp.client.McpPage;
import com.clara.mcp.client.McpServerIdentity;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
public final class DefaultMcpDiscoveryService implements McpDiscoveryService {
    private final McpServerRegistry registry;
    private final McpCapabilityCatalog catalog;
    private final McpClientFactory clientFactory;
    private final List<String> supportedVersions;
    private final int maxPages;
    private final Supplier<String> snapshotIds;
    private final LongSupplier clock;

    public DefaultMcpDiscoveryService(McpServerRegistry registry, McpCapabilityCatalog catalog,
            McpClientFactory clientFactory, List<String> supportedVersions, int maxPages) {
        this(registry, catalog, clientFactory, supportedVersions, maxPages,
                new Supplier<String>() { public String get() { return UUID.randomUUID().toString(); } },
                new LongSupplier() { public long getAsLong() { return System.currentTimeMillis(); } });
    }

    DefaultMcpDiscoveryService(McpServerRegistry registry, McpCapabilityCatalog catalog,
            McpClientFactory clientFactory, List<String> supportedVersions, int maxPages,
            Supplier<String> snapshotIds, LongSupplier clock) {
        if (registry == null || catalog == null || clientFactory == null
                || snapshotIds == null || clock == null) { throw new IllegalArgumentException("dependencies must not be null"); }
        if (supportedVersions == null || supportedVersions.isEmpty() || supportedVersions.contains(null)) {
            throw new IllegalArgumentException("supportedVersions must not be empty");
        }
        if (maxPages <= 0) { throw new IllegalArgumentException("maxPages must be positive"); }
        this.registry = registry; this.catalog = catalog; this.clientFactory = clientFactory;
        this.supportedVersions = Collections.unmodifiableList(new ArrayList<String>(supportedVersions));
        this.maxPages = maxPages; this.snapshotIds = snapshotIds; this.clock = clock;
    }

    public McpServerSnapshot discover(final McpServerRegistration registration) {
        if (registration == null) { throw new IllegalArgumentException("registration must not be null"); }
        McpClient client = clientFactory.create(registration);
        if (client == null) { throw new IllegalStateException("clientFactory returned null"); }
        RuntimeException failure = null;
        try {
            McpInitialization initialization = client.initialize(supportedVersions);
            validateInitialization(registration, initialization);
            List<McpToolDescriptor> tools = initialization.getCapabilities().supportsTools()
                    ? collect(new PageLoader<McpToolDescriptor>() {
                        public McpPage<McpToolDescriptor> load(McpClient value, String cursor) { return value.listTools(cursor); }
                    }, client) : Collections.<McpToolDescriptor>emptyList();
            List<McpResourceDescriptor> resources = initialization.getCapabilities().supportsResources()
                    ? collect(new PageLoader<McpResourceDescriptor>() {
                        public McpPage<McpResourceDescriptor> load(McpClient value, String cursor) { return value.listResources(cursor); }
                    }, client) : Collections.<McpResourceDescriptor>emptyList();
            List<McpResourceTemplateDescriptor> resourceTemplates = initialization.getCapabilities().supportsResources()
                    ? collect(new PageLoader<McpResourceTemplateDescriptor>() {
                        public McpPage<McpResourceTemplateDescriptor> load(McpClient value, String cursor) {
                            return value.listResourceTemplates(cursor);
                        }
                    }, client) : Collections.<McpResourceTemplateDescriptor>emptyList();
            List<McpPromptDescriptor> prompts = initialization.getCapabilities().supportsPrompts()
                    ? collect(new PageLoader<McpPromptDescriptor>() {
                        public McpPage<McpPromptDescriptor> load(McpClient value, String cursor) { return value.listPrompts(cursor); }
                    }, client) : Collections.<McpPromptDescriptor>emptyList();
            return new McpServerSnapshot(snapshotIds.get(), registration.getTenantId(),
                    registration.getServerKey(), clock.getAsLong(), initialization.getProtocolVersion(),
                    initialization.getIdentity(), initialization.getCapabilities(), tools, resources,
                    resourceTemplates, prompts, McpHealthStatus.HEALTHY);
        } catch (RuntimeException exception) {
            failure = exception;
            throw exception;
        } finally {
            try { client.close(); }
            catch (IOException exception) {
                if (failure == null) { throw McpErrors.error(ErrorCodes.MCP_DISCOVERY_FAILED, ErrorCategory.EXTERNAL_SERVICE); }
            }
        }
    }

    public void refresh(String tenantId, String serverKey) {
        McpServerRegistration registration = registry.get(tenantId, serverKey);
        try {
            McpServerSnapshot snapshot = discover(registration);
            catalog.publish(snapshot);
            registry.updateStatus(tenantId, serverKey, McpRegistrationStatus.ACTIVE);
        } catch (ClaraException exception) {
            if (ErrorCodes.MCP_PROTOCOL_INCOMPATIBLE.equals(exception.getError().getCode())) {
                registry.updateStatus(tenantId, serverKey, McpRegistrationStatus.INCOMPATIBLE);
            } else if (ErrorCodes.MCP_SERVER_IDENTITY_CHANGED.equals(exception.getError().getCode())) {
                registry.updateStatus(tenantId, serverKey, McpRegistrationStatus.REVIEW_REQUIRED);
            }
            throw exception;
        }
    }

    private void validateInitialization(McpServerRegistration registration,
            McpInitialization initialization) {
        if (initialization == null || !supportedVersions.contains(initialization.getProtocolVersion())) {
            throw McpErrors.error(ErrorCodes.MCP_PROTOCOL_INCOMPATIBLE, ErrorCategory.CAPABILITY);
        }
        McpServerIdentity identity = initialization.getIdentity();
        if (registration.getApprovedIdentityName() != null
                && !registration.getApprovedIdentityName().equals(identity.getName())) {
            throw McpErrors.error(ErrorCodes.MCP_SERVER_IDENTITY_CHANGED, ErrorCategory.AUTHORIZATION);
        }
        if (registration.getApprovedIdentityVersion() != null
                && !registration.getApprovedIdentityVersion().equals(identity.getVersion())) {
            throw McpErrors.error(ErrorCodes.MCP_SERVER_IDENTITY_CHANGED, ErrorCategory.AUTHORIZATION);
        }
    }

    private <T> List<T> collect(PageLoader<T> loader, McpClient client) {
        List<T> result = new ArrayList<T>();
        Set<String> cursors = new HashSet<String>();
        String cursor = null;
        int pages = 0;
        do {
            if (++pages > maxPages) {
                throw McpErrors.error(ErrorCodes.MCP_PAGINATION_LIMIT_EXCEEDED, ErrorCategory.RESOURCE_LIMIT);
            }
            McpPage<T> page = loader.load(client, cursor);
            if (page == null) { throw McpErrors.error(ErrorCodes.MCP_DISCOVERY_FAILED, ErrorCategory.EXTERNAL_SERVICE); }
            result.addAll(page.getItems());
            cursor = page.getNextCursor();
            if (cursor != null && !cursors.add(cursor)) {
                throw McpErrors.error(ErrorCodes.MCP_PAGINATION_CURSOR_REPEATED, ErrorCategory.EXTERNAL_SERVICE);
            }
        } while (cursor != null);
        return Collections.unmodifiableList(result);
    }

    /**
     * 加载分页。
     */
    private interface PageLoader<T> {
        McpPage<T> load(McpClient client, String cursor);
    }
}
