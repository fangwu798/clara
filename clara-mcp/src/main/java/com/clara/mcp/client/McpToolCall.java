package com.clara.mcp.client;

import com.clara.kernel.InvocationContext;
import com.clara.mcp.registry.ImmutableValues;
import java.util.Map;

/**
 * 表示一次MCP工具调用。
 */
public final class McpToolCall {
    private final String serverKey;
    private final String snapshotId;
    private final String toolName;
    private final String schemaHash;
    private final Map<String, Object> arguments;
    private final InvocationContext context;
    public McpToolCall(String serverKey, String snapshotId, String toolName, String schemaHash,
            Map<String, Object> arguments, InvocationContext context) {
        require(serverKey, "serverKey"); require(snapshotId, "snapshotId");
        require(toolName, "toolName"); require(schemaHash, "schemaHash");
        if (context == null) { throw new IllegalArgumentException("context must not be null"); }
        this.serverKey = serverKey; this.snapshotId = snapshotId; this.toolName = toolName;
        this.schemaHash = schemaHash; this.arguments = ImmutableValues.copyMap(arguments);
        this.context = context;
    }
    public String getServerKey() { return serverKey; }
    public String getSnapshotId() { return snapshotId; }
    public String getToolName() { return toolName; }
    public String getSchemaHash() { return schemaHash; }
    public Map<String, Object> getArguments() { return arguments; }
    public InvocationContext getContext() { return context; }
    private static void require(String value, String field) {
        if (value == null || value.trim().isEmpty()) { throw new IllegalArgumentException(field + " must not be blank"); }
    }
}
