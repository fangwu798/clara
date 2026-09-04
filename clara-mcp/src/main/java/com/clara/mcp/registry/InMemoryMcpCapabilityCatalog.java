package com.clara.mcp.registry;

import com.clara.kernel.ErrorCategory;
import com.clara.kernel.ErrorCodes;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryMcpCapabilityCatalog implements McpCapabilityCatalog {
    private final Map<String, McpServerSnapshot> snapshots =
            new ConcurrentHashMap<String, McpServerSnapshot>();
    private final Map<String, McpServerSnapshot> latest =
            new ConcurrentHashMap<String, McpServerSnapshot>();

    public void publish(McpServerSnapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("snapshot must not be null");
        String snapshotKey = snapshotKey(snapshot.getTenantId(), snapshot.getServerKey(),
                snapshot.getSnapshotId());
        if (snapshots.putIfAbsent(snapshotKey, snapshot) != null) {
            throw new IllegalArgumentException("snapshotId is already published");
        }
        String serverKey = serverKey(snapshot.getTenantId(), snapshot.getServerKey());
        for (;;) {
            McpServerSnapshot current = latest.get(serverKey);
            if (current != null && current.getDiscoveredAtEpochMillis()
                    > snapshot.getDiscoveredAtEpochMillis()) return;
            if (current == null) {
                if (latest.putIfAbsent(serverKey, snapshot) == null) return;
            } else if (latest.replace(serverKey, current, snapshot)) return;
        }
    }
    public McpServerSnapshot latest(String tenantId, String serverKey) {
        McpServerSnapshot snapshot = latest.get(serverKey(tenantId, serverKey));
        if (snapshot == null) throw notFound();
        return snapshot;
    }
    public McpServerSnapshot get(String tenantId, String serverKey, String snapshotId) {
        McpServerSnapshot snapshot = snapshots.get(snapshotKey(tenantId, serverKey, snapshotId));
        if (snapshot == null) throw notFound();
        return snapshot;
    }
    public McpToolDescriptor resolveTool(String tenantId, String serverKey, String snapshotId,
            String toolName, String expectedSchemaHash) {
        if (toolName == null || toolName.trim().isEmpty()) throw new IllegalArgumentException("toolName must not be blank");
        if (expectedSchemaHash == null || expectedSchemaHash.trim().isEmpty()) {
            throw new IllegalArgumentException("expectedSchemaHash must not be blank");
        }
        for (McpToolDescriptor tool : get(tenantId, serverKey, snapshotId).getTools()) {
            if (toolName.equals(tool.getName())) {
                if (!expectedSchemaHash.equals(tool.getSchemaHash())) {
                    throw McpErrors.error(ErrorCodes.MCP_CAPABILITY_CHANGED, ErrorCategory.CAPABILITY);
                }
                return tool;
            }
        }
        throw notFound();
    }
    public McpResourceDescriptor resolveResource(String tenantId, String serverKey, String snapshotId,
            String uri, String expectedSchemaHash) {
        require(uri, "uri"); require(expectedSchemaHash, "expectedSchemaHash");
        for (McpResourceDescriptor resource : get(tenantId, serverKey, snapshotId).getResources()) {
            if (uri.equals(resource.getUri())) {
                requireHash(expectedSchemaHash, resource.getSchemaHash()); return resource;
            }
        }
        throw notFound();
    }
    public McpPromptDescriptor resolvePrompt(String tenantId, String serverKey, String snapshotId,
            String promptName, String expectedSchemaHash) {
        require(promptName, "promptName"); require(expectedSchemaHash, "expectedSchemaHash");
        for (McpPromptDescriptor prompt : get(tenantId, serverKey, snapshotId).getPrompts()) {
            if (promptName.equals(prompt.getName())) {
                requireHash(expectedSchemaHash, prompt.getSchemaHash()); return prompt;
            }
        }
        throw notFound();
    }
    private static void requireHash(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw McpErrors.error(ErrorCodes.MCP_CAPABILITY_CHANGED, ErrorCategory.CAPABILITY);
        }
    }
    private static String serverKey(String tenantId, String serverKey) {
        require(tenantId, "tenantId"); require(serverKey, "serverKey");
        return tenantId + '\u0000' + serverKey;
    }
    private static String snapshotKey(String tenantId, String serverKey, String snapshotId) {
        require(snapshotId, "snapshotId"); return serverKey(tenantId, serverKey) + '\u0000' + snapshotId;
    }
    private static void require(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
    }
    private static RuntimeException notFound() {
        return McpErrors.error(ErrorCodes.MCP_CAPABILITY_NOT_FOUND, ErrorCategory.CAPABILITY);
    }
}
