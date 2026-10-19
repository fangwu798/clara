package com.clara.mcp.client.gateway;

import com.clara.context.CallContext;
import com.clara.enums.ErrorCategoryEnum;
import com.clara.exception.ErrorCodes;
import com.clara.mcp.McpErrors;
import com.clara.mcp.api.IMcpCapabilityCatalog;
import com.clara.mcp.api.IMcpClient;
import com.clara.mcp.api.IMcpClientFactory;
import com.clara.mcp.api.IMcpGateway;
import com.clara.mcp.api.IMcpServerRegistry;
import com.clara.mcp.client.McpInitialization;
import com.clara.mcp.client.call.McpPromptRequest;
import com.clara.mcp.client.call.McpPromptResult;
import com.clara.mcp.client.call.McpResourceContent;
import com.clara.mcp.client.call.McpResourceRequest;
import com.clara.mcp.client.call.McpToolCall;
import com.clara.mcp.client.call.McpToolResult;
import com.clara.mcp.registry.McpHealthStatus;
import com.clara.mcp.registry.McpRegistrationStatus;
import com.clara.mcp.registry.McpServerRegistration;
import com.clara.mcp.registry.McpServerSnapshot;
import java.io.IOException;
import java.util.Collections;
import java.util.function.Function;

/**
 * 在调用 MCP 工具、资源和提示词前统一校验注册状态、能力快照、Schema 与服务端身份。
 *
 * <p>常规用法：先通过发现服务发布健康快照，再使用 {@link #callTool}、{@link #readResource} 或
 * {@link #getPrompt}；请求应携带发现时的 snapshot 标识和 Schema 哈希。
 *
 * <p>注意：仅 ACTIVE 且快照健康的服务端可调用。网关每次调用都会创建临时客户端并核对协议版本和身份，
 * 能力已变化时会拒绝执行；客户端由网关关闭，调用方不得复用或管理该实例。服务端所属租户由调用请求显式携带
 * （会话上下文已不再携带租户标识），网关据此在按租户分区的注册表与能力目录中定位服务端。
 */
public final class DefaultMcpGateway implements IMcpGateway {
    private final IMcpServerRegistry registry;
    private final IMcpCapabilityCatalog catalog;
    private final IMcpClientFactory clientFactory;

    public DefaultMcpGateway(IMcpServerRegistry registry, IMcpCapabilityCatalog catalog,
            IMcpClientFactory clientFactory) {
        if (registry == null || catalog == null || clientFactory == null) {
            throw new IllegalArgumentException("dependencies must not be null");
        }
        this.registry = registry;
        this.catalog = catalog;
        this.clientFactory = clientFactory;
    }

    public McpToolResult callTool(final McpToolCall call) {
        if (call == null) { throw new IllegalArgumentException("call must not be null"); }
        Prepared prepared = prepare(call.getTenantId(), call.getCall(), call.getServerKey(), call.getSnapshotId());
        catalog.resolveTool(call.getTenantId(), call.getServerKey(), call.getSnapshotId(),
                call.getToolName(), call.getSchemaHash());
        return execute(prepared, client -> client.callTool(call));
    }

    public McpResourceContent readResource(final McpResourceRequest request) {
        if (request == null) { throw new IllegalArgumentException("request must not be null"); }
        Prepared prepared = prepare(request.getTenantId(), request.getCall(), request.getServerKey(),
                request.getSnapshotId());
        catalog.resolveResource(request.getTenantId(), request.getServerKey(),
                request.getSnapshotId(), request.getUri(), request.getSchemaHash());
        return execute(prepared, client -> client.readResource(request));
    }

    public McpPromptResult getPrompt(final McpPromptRequest request) {
        if (request == null) { throw new IllegalArgumentException("request must not be null"); }
        Prepared prepared = prepare(request.getTenantId(), request.getCall(), request.getServerKey(),
                request.getSnapshotId());
        catalog.resolvePrompt(request.getTenantId(), request.getServerKey(),
                request.getSnapshotId(), request.getPromptName(), request.getSchemaHash());
        return execute(prepared, client -> client.getPrompt(request));
    }

    /**
     * 校验注册状态、快照健康度并解析出可用快照。
     *
     * @param tenantId   服务端所属租户，来自调用请求（会话上下文已不携带租户），不可为空白
     * @param call       本次调用的上下文，用于取消检查，不可为 null
     * @param serverKey  服务端键，不可为空白
     * @param snapshotId 发现时固化的快照 ID，不可为空白
     * @return 已校验的注册与快照，非 null
     */
    private Prepared prepare(String tenantId, CallContext call, String serverKey, String snapshotId) {
        call.getCancellationToken().throwIfCancellationRequested(call.getTraceId());
        McpServerRegistration registration = registry.get(tenantId, serverKey);
        if (registration == null) {
            throw McpErrors.error(ErrorCodes.MCP_SERVER_NOT_FOUND, ErrorCategoryEnum.CONFIGURATION);
        }
        if (registration.getStatus() != McpRegistrationStatus.ACTIVE) {
            throw McpErrors.error(ErrorCodes.MCP_SERVER_NOT_ACTIVE, ErrorCategoryEnum.AUTHORIZATION);
        }
        McpServerSnapshot snapshot = catalog.get(tenantId, serverKey, snapshotId);
        if (snapshot == null) {
            throw McpErrors.error(ErrorCodes.MCP_CAPABILITY_NOT_FOUND, ErrorCategoryEnum.CAPABILITY);
        }
        if (snapshot.getHealthStatus() != McpHealthStatus.HEALTHY) {
            throw McpErrors.error(ErrorCodes.MCP_SERVER_NOT_ACTIVE, ErrorCategoryEnum.EXTERNAL_SERVICE);
        }
        return new Prepared(registration, snapshot);
    }

    /**
     * 创建临时客户端、核对协议版本与身份、执行操作，并在结束时关闭客户端。
     *
     * @param prepared  已校验的注册与快照，非 null
     * @param operation 在客户端上执行的操作，非 null
     * @param <T>       结果类型
     * @return 操作结果，非 null
     */
    private <T> T execute(Prepared prepared, Function<IMcpClient, T> operation) {
        IMcpClient client = clientFactory.create(prepared.registration);
        RuntimeException failure = null;
        try {
            McpInitialization initialization = client.initialize(
                    Collections.singletonList(prepared.snapshot.getProtocolVersion()));
            if (!prepared.snapshot.getProtocolVersion().equals(initialization.getProtocolVersion())
                    || !prepared.snapshot.getIdentity().equals(initialization.getIdentity())) {
                throw McpErrors.error(ErrorCodes.MCP_CAPABILITY_CHANGED, ErrorCategoryEnum.CAPABILITY);
            }
            return operation.apply(client);
        } catch (RuntimeException exception) {
            failure = exception;
            throw exception;
        } finally {
            try {
                client.close();
            } catch (IOException exception) {
                if (failure == null) {
                    throw McpErrors.error(ErrorCodes.MCP_DISCOVERY_FAILED,
                            ErrorCategoryEnum.EXTERNAL_SERVICE);
                }
            }
        }
    }

    /**
     * 已通过校验的注册与快照。
     */
    private static final class Prepared {
        private final McpServerRegistration registration;
        private final McpServerSnapshot snapshot;

        private Prepared(McpServerRegistration registration, McpServerSnapshot snapshot) {
            this.registration = registration;
            this.snapshot = snapshot;
        }
    }
}
