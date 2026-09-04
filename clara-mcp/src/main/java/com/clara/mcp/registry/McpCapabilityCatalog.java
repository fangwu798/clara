package com.clara.mcp.registry;

public interface McpCapabilityCatalog {
    void publish(McpServerSnapshot snapshot);
    McpServerSnapshot latest(String tenantId, String serverKey);
    McpServerSnapshot get(String tenantId, String serverKey, String snapshotId);
    McpToolDescriptor resolveTool(String tenantId, String serverKey, String snapshotId,
            String toolName, String expectedSchemaHash);
    McpResourceDescriptor resolveResource(String tenantId, String serverKey, String snapshotId,
            String uri, String expectedSchemaHash);
    McpPromptDescriptor resolvePrompt(String tenantId, String serverKey, String snapshotId,
            String promptName, String expectedSchemaHash);
}
