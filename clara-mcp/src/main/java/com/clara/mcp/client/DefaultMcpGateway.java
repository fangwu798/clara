package com.clara.mcp.client;

import com.clara.kernel.ErrorCategory;
import com.clara.kernel.ErrorCodes;
import com.clara.kernel.InvocationContext;
import com.clara.mcp.registry.McpCapabilityCatalog;
import com.clara.mcp.registry.McpClientFactory;
import com.clara.mcp.registry.McpHealthStatus;
import com.clara.mcp.registry.McpRegistrationStatus;
import com.clara.mcp.registry.McpServerRegistration;
import com.clara.mcp.registry.McpServerRegistry;
import com.clara.mcp.registry.McpServerSnapshot;
import java.io.IOException;
import java.util.Arrays;

/**
 * 在调用 MCP 工具、资源和提示词前统一校验注册状态、能力快照、Schema 与服务端身份。
 *
 * <p>常规用法：先通过发现服务发布健康快照，再使用 {@link #callTool}、{@link #readResource} 或
 * {@link #getPrompt}；请求应携带发现时的 snapshot ID 和 Schema 哈希。
 *
 * <p>注意：仅 ACTIVE 且快照健康的服务端可调用。网关每次调用都会重新初始化临时客户端并核对协议版本和身份，
 * 能力已变化时会拒绝执行；客户端由网关关闭，调用方不得复用或管理该实例。
 */
public final class DefaultMcpGateway implements McpGateway {
    private final McpServerRegistry registry;
    private final McpCapabilityCatalog catalog;
    private final McpClientFactory clientFactory;

    public DefaultMcpGateway(McpServerRegistry registry, McpCapabilityCatalog catalog,
            McpClientFactory clientFactory) {
        if (registry == null || catalog == null || clientFactory == null) {
            throw new IllegalArgumentException("dependencies must not be null");
        }
        this.registry = registry; this.catalog = catalog; this.clientFactory = clientFactory;
    }

    public McpToolResult callTool(final McpToolCall call) {
        if (call == null) { throw new IllegalArgumentException("call must not be null"); }
        Prepared prepared = prepare(call.getContext(), call.getServerKey(), call.getSnapshotId());
        catalog.resolveTool(call.getContext().getTenantId(), call.getServerKey(), call.getSnapshotId(),
                call.getToolName(), call.getSchemaHash());
        return execute(prepared, new Operation<McpToolResult>() {
            public McpToolResult run(McpClient client) {
                return client.callTool(call.getToolName(), call.getArguments());
            }
        });
    }

    public McpResourceContent readResource(final McpResourceRequest request) {
        if (request == null) { throw new IllegalArgumentException("request must not be null"); }
        Prepared prepared = prepare(request.getContext(), request.getServerKey(), request.getSnapshotId());
        catalog.resolveResource(request.getContext().getTenantId(), request.getServerKey(),
                request.getSnapshotId(), request.getUri(), request.getSchemaHash());
        return execute(prepared, new Operation<McpResourceContent>() {
            public McpResourceContent run(McpClient client) { return client.readResource(request.getUri()); }
        });
    }

    public McpPromptResult getPrompt(final McpPromptRequest request) {
        if (request == null) { throw new IllegalArgumentException("request must not be null"); }
        Prepared prepared = prepare(request.getContext(), request.getServerKey(), request.getSnapshotId());
        catalog.resolvePrompt(request.getContext().getTenantId(), request.getServerKey(),
                request.getSnapshotId(), request.getPromptName(), request.getSchemaHash());
        return execute(prepared, new Operation<McpPromptResult>() {
            public McpPromptResult run(McpClient client) {
                return client.getPrompt(request.getPromptName(), request.getArguments());
            }
        });
    }

    private Prepared prepare(InvocationContext context, String serverKey, String snapshotId) {
        context.getCancellationToken().throwIfCancellationRequested(context.getTraceId());
        String tenantId = context.getTenantId();
        McpServerRegistration registration = registry.get(tenantId, serverKey);
        if (registration.getStatus() != McpRegistrationStatus.ACTIVE) {
            throw McpClientErrors.error(ErrorCodes.MCP_SERVER_NOT_ACTIVE, ErrorCategory.AUTHORIZATION);
        }
        McpServerSnapshot snapshot = catalog.get(tenantId, serverKey, snapshotId);
        if (snapshot.getHealthStatus() != McpHealthStatus.HEALTHY) {
            throw McpClientErrors.error(ErrorCodes.MCP_SERVER_NOT_ACTIVE, ErrorCategory.EXTERNAL_SERVICE);
        }
        return new Prepared(registration, snapshot);
    }

    private <T> T execute(Prepared prepared, Operation<T> operation) {
        McpClient client = clientFactory.create(prepared.registration);
        RuntimeException failure = null;
        try {
            McpInitialization initialization = client.initialize(
                    Arrays.asList(prepared.snapshot.getProtocolVersion()));
            if (!prepared.snapshot.getProtocolVersion().equals(initialization.getProtocolVersion())
                    || !prepared.snapshot.getIdentity().equals(initialization.getIdentity())) {
                throw McpClientErrors.error(ErrorCodes.MCP_CAPABILITY_CHANGED, ErrorCategory.CAPABILITY);
            }
            return operation.run(client);
        } catch (RuntimeException exception) {
            failure = exception; throw exception;
        } finally {
            try { client.close(); }
            catch (IOException exception) {
                if (failure == null) {
                    throw McpClientErrors.error(ErrorCodes.MCP_DISCOVERY_FAILED,
                            ErrorCategory.EXTERNAL_SERVICE);
                }
            }
        }
    }

    /**
     * 定义。
     */
    private interface Operation<T> { T run(McpClient client); }
    /**
     * 表示。
     */
    private static final class Prepared {
        private final McpServerRegistration registration;
        private final McpServerSnapshot snapshot;
        private Prepared(McpServerRegistration registration, McpServerSnapshot snapshot) {
            this.registration = registration; this.snapshot = snapshot;
        }
    }
}
