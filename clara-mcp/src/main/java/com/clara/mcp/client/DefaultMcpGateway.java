//package com.clara.mcp.client;
//
//import com.clara.context.CallContext;
//import com.clara.kernel.exception.ErrorCategory;
//import com.clara.kernel.exception.ErrorCodes;
//import com.clara.mcp.registry.McpCapabilityCatalog;
//import com.clara.mcp.registry.McpClientFactory;
//import com.clara.mcp.registry.McpHealthStatus;
//import com.clara.mcp.registry.McpRegistrationStatus;
//import com.clara.mcp.registry.McpServerRegistration;
//import com.clara.mcp.registry.McpServerRegistry;
//import com.clara.mcp.registry.McpServerSnapshot;
//import java.io.IOException;
//import java.util.Arrays;
//
///**
// * 在调用 MCP 工具、资源和提示词前统一校验注册状态、能力快照、Schema 与服务端身份。
// *
// * <p>常规用法：先通过发现服务发布健康快照，再使用 {@link #callTool}、{@link #readResource} 或
// * {@link #getPrompt}；请求应携带发现时的 snapshot ID 和 Schema 哈希。
// *
// * <p>注意：仅 ACTIVE 且快照健康的服务端可调用。网关每次调用都会重新初始化临时客户端并核对协议版本和身份，
// * 能力已变化时会拒绝执行；客户端由网关关闭，调用方不得复用或管理该实例。服务端所属租户由调用请求显式携带
// * （会话上下文已不再携带租户标识），网关据此在按租户分区的注册表与能力目录中定位服务端。
// */
//public final class DefaultMcpGateway implements McpGateway {
//    private final McpServerRegistry registry;
//    private final McpCapabilityCatalog catalog;
//    private final McpClientFactory clientFactory;
//
//    public DefaultMcpGateway(McpServerRegistry registry, McpCapabilityCatalog catalog,
//            McpClientFactory clientFactory) {
//        if (registry == null || catalog == null || clientFactory == null) {
//            throw new IllegalArgumentException("dependencies must not be null");
//        }
//        this.registry = registry; this.catalog = catalog; this.clientFactory = clientFactory;
//    }
//
//    public McpToolResult callTool(final McpToolCall call) {
//        if (call == null) { throw new IllegalArgumentException("call must not be null"); }
//        Prepared prepared = prepare(call.getTenantId(), call.getCall(),
//                call.getServerKey(), call.getSnapshotId());
//        catalog.resolveTool(call.getTenantId(), call.getServerKey(), call.getSnapshotId(),
//                call.getToolName(), call.getSchemaHash());
//        return execute(prepared, new Operation<McpToolResult>() {
//            public McpToolResult run(McpClient client) {
//                return client.callTool(call.getToolName(), call.getArguments());
//            }
//        });
//    }
//
//    public McpResourceContent readResource(final McpResourceRequest request) {
//        if (request == null) { throw new IllegalArgumentException("request must not be null"); }
//        Prepared prepared = prepare(request.getTenantId(), request.getCall(), request.getServerKey(),
//                request.getSnapshotId());
//        catalog.resolveResource(request.getTenantId(), request.getServerKey(),
//                request.getSnapshotId(), request.getUri(), request.getSchemaHash());
//        return execute(prepared, new Operation<McpResourceContent>() {
//            public McpResourceContent run(McpClient client) { return client.readResource(request.getUri()); }
//        });
//    }
//
//    public McpPromptResult getPrompt(final McpPromptRequest request) {
//        if (request == null) { throw new IllegalArgumentException("request must not be null"); }
//        Prepared prepared = prepare(request.getTenantId(), request.getCall(), request.getServerKey(),
//                request.getSnapshotId());
//        catalog.resolvePrompt(request.getTenantId(), request.getServerKey(),
//                request.getSnapshotId(), request.getPromptName(), request.getSchemaHash());
//        return execute(prepared, new Operation<McpPromptResult>() {
//            public McpPromptResult run(McpClient client) {
//                return client.getPrompt(request.getPromptName(), request.getArguments());
//            }
//        });
//    }
//
//    /**
//     * 校验注册状态、快照健康度并解析出可用快照。
//     *
//     * @param tenantId   服务端所属租户，来自调用请求（会话上下文已不携带租户），不可为空白
//     * @param call       本次调用的上下文，用于取消检查，不可为 null
//     * @param serverKey  服务端键，不可为空白
//     * @param snapshotId 发现时固化的快照 ID，不可为空白
//     * @return 已校验的注册与快照，非 null
//     */
//    private Prepared prepare(String tenantId, CallContext call, String serverKey, String snapshotId) {
//        call.getCancellationToken().throwIfCancellationRequested(call.getTraceId());
//        McpServerRegistration registration = registry.get(tenantId, serverKey);
//        if (registration.getStatus() != McpRegistrationStatus.ACTIVE) {
//            throw McpClientErrors.error(ErrorCodes.MCP_SERVER_NOT_ACTIVE, ErrorCategory.AUTHORIZATION);
//        }
//        McpServerSnapshot snapshot = catalog.get(tenantId, serverKey, snapshotId);
//        if (snapshot.getHealthStatus() != McpHealthStatus.HEALTHY) {
//            throw McpClientErrors.error(ErrorCodes.MCP_SERVER_NOT_ACTIVE, ErrorCategory.EXTERNAL_SERVICE);
//        }
//        return new Prepared(registration, snapshot);
//    }
//
//    private <T> T execute(Prepared prepared, Operation<T> operation) {
//        McpClient client = clientFactory.create(prepared.registration);
//        RuntimeException failure = null;
//        try {
//            McpInitialization initialization = client.initialize(
//                    Arrays.asList(prepared.snapshot.getProtocolVersion()));
//            if (!prepared.snapshot.getProtocolVersion().equals(initialization.getProtocolVersion())
//                    || !prepared.snapshot.getIdentity().equals(initialization.getIdentity())) {
//                throw McpClientErrors.error(ErrorCodes.MCP_CAPABILITY_CHANGED, ErrorCategory.CAPABILITY);
//            }
//            return operation.run(client);
//        } catch (RuntimeException exception) {
//            failure = exception; throw exception;
//        } finally {
//            try { client.close(); }
//            catch (IOException exception) {
//                if (failure == null) {
//                    throw McpClientErrors.error(ErrorCodes.MCP_DISCOVERY_FAILED,
//                            ErrorCategory.EXTERNAL_SERVICE);
//                }
//            }
//        }
//    }
//
//    /**
//     * 定义。
//     */
//    private interface Operation<T> { T run(McpClient client); }
//    /**
//     * 表示。
//     */
//    private static final class Prepared {
//        private final McpServerRegistration registration;
//        private final McpServerSnapshot snapshot;
//        private Prepared(McpServerRegistration registration, McpServerSnapshot snapshot) {
//            this.registration = registration; this.snapshot = snapshot;
//        }
//    }
//}
