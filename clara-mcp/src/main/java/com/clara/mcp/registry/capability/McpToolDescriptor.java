package com.clara.mcp.registry.capability;

import com.clara.mcp.registry.ImmutableValues;
import java.util.Map;

/**
 * 描述MCP工具。
 */
public final class McpToolDescriptor {
    private final String name;
    private final String description;
    private final Map<String, Object> inputSchema;
    private final String schemaHash;

    public McpToolDescriptor(String name, String description, Map<String, Object> inputSchema) {
        if (name == null || name.trim().isEmpty()) { throw new IllegalArgumentException("name must not be blank"); }
        this.name = name;
        this.description = description;
        this.inputSchema = ImmutableValues.copyMap(inputSchema);
        this.schemaHash = new SchemaHasher().hash(this.inputSchema);
    }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Map<String, Object> getInputSchema() { return inputSchema; }
    public String getSchemaHash() { return schemaHash; }
}
