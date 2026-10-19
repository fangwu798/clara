package com.clara.mcp.client.call;

import com.clara.context.CallContext;
import com.clara.context.ConvContext;

/**
 * 封装MCP资源操作的输入参数：服务端身份（租户 + 服务端键 + 快照）、资源 URI 与 Schema 哈希、上下文。
 *
 * <p>是什么、在哪一层：{@code clara-mcp} 客户端的调用入参值对象，由宿主应用创建后交给
 * {@code IMcpGateway.readResource}；网关按其中的 {@code tenantId}、{@code serverKey} 定位服务端并核对
 * {@code schemaHash} 后读取资源。
 *
 * <p>为什么显式携带租户：MCP 服务端注册表与能力目录按租户分区，而会话上下文 {@link ConvContext} 已不再
 * 携带租户标识，因此本类显式携带 {@code tenantId}。
 *
 * <p>使用方法：{@code new McpResourceRequest(tenantId, serverKey, snapshotId, uri, schemaHash, conversation,
 * call)}。
 *
 * <p>生命周期与线程安全：构造后不可变，可跨线程共享。
 *
 * <p>失败语义：{@code tenantId}、{@code serverKey}、{@code snapshotId}、{@code uri}、{@code schemaHash} 为
 * 空白，或 {@code conversation} / {@code call} 为 null 时构造抛 {@link IllegalArgumentException}。
 */
public final class McpResourceRequest {
    private final String tenantId;
    private final String serverKey;
    private final String snapshotId;
    private final String uri;
    private final String schemaHash;
    private final ConvContext conversation;
    private final CallContext call;

    public McpResourceRequest(String tenantId, String serverKey, String snapshotId, String uri, String schemaHash,
                              ConvContext conversation, CallContext call) {
        if (isBlank(tenantId) || isBlank(serverKey) || isBlank(snapshotId) || isBlank(uri) || isBlank(schemaHash)) {
            throw new IllegalArgumentException("resource request identifiers must not be blank");
        }
        if (conversation == null) { throw new IllegalArgumentException("conversation must not be null"); }
        if (call == null) { throw new IllegalArgumentException("call must not be null"); }
        this.tenantId = tenantId;
        this.serverKey = serverKey;
        this.snapshotId = snapshotId;
        this.uri = uri;
        this.schemaHash = schemaHash;
        this.conversation = conversation;
        this.call = call;
    }

    /**
     * 服务端所属租户标识，用于在按租户分区的注册表与能力目录中定位服务端。
     *
     * @return 非空白租户标识
     */
    public String getTenantId() { return tenantId; }
    public String getServerKey() { return serverKey; }
    public String getSnapshotId() { return snapshotId; }
    public String getUri() { return uri; }
    public String getSchemaHash() { return schemaHash; }
    public ConvContext getConversation() { return conversation; }
    public CallContext getCall() { return call; }

    private static boolean isBlank(String value) { return value == null || value.trim().isEmpty(); }
}
