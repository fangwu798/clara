package com.clara.mcp.client;

import com.clara.kernel.InvocationContext;

/**
 * 封装MCP资源操作的输入参数。
 */
public final class McpResourceRequest {
    private final String serverKey, snapshotId, uri, schemaHash;
    private final InvocationContext context;
    public McpResourceRequest(String serverKey, String snapshotId, String uri, String schemaHash,
            InvocationContext context) {
        if (isBlank(serverKey) || isBlank(snapshotId) || isBlank(uri) || isBlank(schemaHash)) {
            throw new IllegalArgumentException("resource request identifiers must not be blank");
        }
        if (context == null) { throw new IllegalArgumentException("context must not be null"); }
        this.serverKey = serverKey; this.snapshotId = snapshotId; this.uri = uri;
        this.schemaHash = schemaHash; this.context = context;
    }
    public String getServerKey() { return serverKey; }
    public String getSnapshotId() { return snapshotId; }
    public String getUri() { return uri; }
    public String getSchemaHash() { return schemaHash; }
    public InvocationContext getContext() { return context; }
    private static boolean isBlank(String value) { return value == null || value.trim().isEmpty(); }
}
