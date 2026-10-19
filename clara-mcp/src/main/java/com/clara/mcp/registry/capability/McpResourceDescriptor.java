package com.clara.mcp.registry.capability;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 描述MCP资源。
 */
public final class McpResourceDescriptor {
    private final String uri;
    private final String name;
    private final String mimeType;
    private final String schemaHash;
    public McpResourceDescriptor(String uri, String name, String mimeType) {
        if (uri == null || uri.trim().isEmpty()) { throw new IllegalArgumentException("uri must not be blank"); }
        this.uri = uri;
        this.name = name;
        this.mimeType = mimeType;
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("uri", uri); value.put("name", name); value.put("mimeType", mimeType);
        this.schemaHash = new SchemaHasher().hash(value);
    }
    public String getUri() { return uri; }
    public String getName() { return name; }
    public String getMimeType() { return mimeType; }
    public String getSchemaHash() { return schemaHash; }
}
