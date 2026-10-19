package com.clara.mcp.registry;

import com.clara.enums.ErrorCategoryEnum;
import com.clara.exception.ErrorCodes;
import com.clara.mcp.McpErrors;
import com.clara.mcp.api.IMcpCapabilityCatalog;
import com.clara.mcp.registry.capability.McpPromptDescriptor;
import com.clara.mcp.registry.capability.McpResourceDescriptor;
import com.clara.mcp.registry.capability.McpToolDescriptor;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提供MCP能力目录的内存实现。
 */
public final class InMemoryMcpCapabilityCatalog implements IMcpCapabilityCatalog {

    private static final String FIELD_EXPECTED_SCHEMA_HASH = "expectedSchemaHash";
    private final Map<String, McpServerSnapshot> snapshots =
            new ConcurrentHashMap<>();
    private final Map<String, McpServerSnapshot> latest =
            new ConcurrentHashMap<>();

    public void publish(McpServerSnapshot snapshot) {
        if (snapshot == null) { throw new IllegalArgumentException("snapshot must not be null"); }
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
        return latest.get(serverKey(tenantId, serverKey));
    }
    public McpServerSnapshot get(String tenantId, String serverKey, String snapshotId) {
        return snapshots.get(snapshotKey(tenantId, serverKey, snapshotId));
    }
    public McpToolDescriptor resolveTool(String tenantId, String serverKey, String snapshotId,
            String toolName, String expectedSchemaHash) {
        require(toolName, "toolName"); require(expectedSchemaHash, FIELD_EXPECTED_SCHEMA_HASH);
        McpServerSnapshot snapshot = requireSnapshot(tenantId, serverKey, snapshotId);
        for (McpToolDescriptor tool : snapshot.getTools()) {
            if (toolName.equals(tool.getName())) {
                requireHash(expectedSchemaHash, tool.getSchemaHash()); return tool;
            }
        }
        throw notFound();
    }
    public McpResourceDescriptor resolveResource(String tenantId, String serverKey, String snapshotId,
            String uri, String expectedSchemaHash) {
        require(uri, "uri"); require(expectedSchemaHash, FIELD_EXPECTED_SCHEMA_HASH);
        McpServerSnapshot snapshot = requireSnapshot(tenantId, serverKey, snapshotId);
        for (McpResourceDescriptor resource : snapshot.getResources()) {
            if (uri.equals(resource.getUri())) {
                requireHash(expectedSchemaHash, resource.getSchemaHash()); return resource;
            }
        }
        throw notFound();
    }
    public McpPromptDescriptor resolvePrompt(String tenantId, String serverKey, String snapshotId,
            String promptName, String expectedSchemaHash) {
        require(promptName, "promptName"); require(expectedSchemaHash, FIELD_EXPECTED_SCHEMA_HASH);
        McpServerSnapshot snapshot = requireSnapshot(tenantId, serverKey, snapshotId);
        for (McpPromptDescriptor prompt : snapshot.getPrompts()) {
            if (promptName.equals(prompt.getName())) {
                requireHash(expectedSchemaHash, prompt.getSchemaHash()); return prompt;
            }
        }
        throw notFound();
    }
    private McpServerSnapshot requireSnapshot(String tenantId, String serverKey, String snapshotId) {
        McpServerSnapshot snapshot = get(tenantId, serverKey, snapshotId);
        if (snapshot == null) { throw notFound(); }
        return snapshot;
    }
    private static void requireHash(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw McpErrors.error(ErrorCodes.MCP_SCHEMA_MISMATCH, ErrorCategoryEnum.CAPABILITY);
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
        if (value == null || value.trim().isEmpty()) { throw new IllegalArgumentException(field + " must not be blank"); }
    }
    private static RuntimeException notFound() {
        return McpErrors.error(ErrorCodes.MCP_CAPABILITY_NOT_FOUND, ErrorCategoryEnum.CAPABILITY);
    }
}
