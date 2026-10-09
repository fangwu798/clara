package com.clara.mcp.client.call;

import com.clara.context.CallContext;
import com.clara.context.ConvContext;
import com.clara.mcp.registry.ImmutableValues;
import java.util.Map;

/**
 * 表示一次MCP工具调用：服务端身份（租户 + 服务端键 + 快照）、工具名与 Schema 哈希、参数与上下文。
 *
 * <p>是什么、在哪一层：{@code clara-mcp} 客户端的调用入参值对象，由宿主应用创建后交给
 * {@code McpGateway.callTool}；网关按其中的 {@code tenantId}、{@code serverKey} 在注册表与能力目录中定位
 * 服务端，并核对 {@code schemaHash} 后再发起调用。
 *
 * <p>为什么显式携带租户：MCP 服务端注册表与能力目录按租户分区（同一 {@code serverKey} 在不同租户下是不同
 * 的服务端），而会话上下文 {@link ConvContext} 已不再携带租户标识，因此本类显式携带 {@code tenantId}。
 *
 * <p>使用方法：{@code new McpToolCall(tenantId, serverKey, snapshotId, toolName, schemaHash, arguments,
 * conversation, call)}；参数 map 会被拷贝为只读视图。
 *
 * <p>生命周期与线程安全：构造后不可变，可跨线程共享。
 *
 * <p>失败语义：{@code tenantId}、{@code serverKey}、{@code snapshotId}、{@code toolName}、{@code schemaHash}
 * 为空白，或 {@code conversation} / {@code call} 为 null 时构造抛 {@link IllegalArgumentException}。
 */
public final class McpToolCall {
    private final String tenantId;
    private final String serverKey;
    private final String snapshotId;
    private final String toolName;
    private final String schemaHash;
    private final Map<String, Object> arguments;
    private final ConvContext conversation;
    private final CallContext call;

    public McpToolCall(String tenantId, String serverKey, String snapshotId, String toolName, String schemaHash,
                       Map<String, Object> arguments, ConvContext conversation, CallContext call) {
        require(tenantId, "tenantId");
        require(serverKey, "serverKey");
        require(snapshotId, "snapshotId");
        require(toolName, "toolName");
        require(schemaHash, "schemaHash");
        if (conversation == null) { throw new IllegalArgumentException("conversation must not be null"); }
        if (call == null) { throw new IllegalArgumentException("call must not be null"); }
        this.tenantId = tenantId;
        this.serverKey = serverKey;
        this.snapshotId = snapshotId;
        this.toolName = toolName;
        this.schemaHash = schemaHash;
        this.arguments = ImmutableValues.copyMap(arguments);
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
    public String getToolName() { return toolName; }
    public String getSchemaHash() { return schemaHash; }
    public Map<String, Object> getArguments() { return arguments; }
    public ConvContext getConversation() { return conversation; }
    public CallContext getCall() { return call; }

    private static void require(String value, String field) {
        if (value == null || value.trim().isEmpty()) { throw new IllegalArgumentException(field + " must not be blank"); }
    }
}
