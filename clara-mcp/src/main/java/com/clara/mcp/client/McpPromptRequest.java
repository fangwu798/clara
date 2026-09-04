package com.clara.mcp.client;

import com.clara.kernel.InvocationContext;
import com.clara.mcp.registry.ImmutableValues;
import java.util.Map;

public final class McpPromptRequest {
    private final String serverKey, snapshotId, promptName, schemaHash;
    private final Map<String, Object> arguments;
    private final InvocationContext context;
    public McpPromptRequest(String serverKey, String snapshotId, String promptName,
            String schemaHash, Map<String, Object> arguments, InvocationContext context) {
        if (isBlank(serverKey) || isBlank(snapshotId) || isBlank(promptName) || isBlank(schemaHash)) {
            throw new IllegalArgumentException("prompt request identifiers must not be blank");
        }
        if (context == null) throw new IllegalArgumentException("context must not be null");
        this.serverKey = serverKey; this.snapshotId = snapshotId; this.promptName = promptName;
        this.schemaHash = schemaHash; this.arguments = ImmutableValues.copyMap(arguments);
        this.context = context;
    }
    public String getServerKey() { return serverKey; }
    public String getSnapshotId() { return snapshotId; }
    public String getPromptName() { return promptName; }
    public String getSchemaHash() { return schemaHash; }
    public Map<String, Object> getArguments() { return arguments; }
    public InvocationContext getContext() { return context; }
    private static boolean isBlank(String value) { return value == null || value.trim().isEmpty(); }
}
