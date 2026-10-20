package com.clara.mcp.registry.capability;

import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 描述MCP资源模板。
 */
@Getter
public final class McpResourceTemplateDescriptor {
    private final String uriTemplate;
    private final String name;
    private final String mimeType;
    private final String schemaHash;

    public McpResourceTemplateDescriptor(String uriTemplate, String name, String mimeType) {
        if (uriTemplate == null || uriTemplate.trim().isEmpty()) {
            throw new IllegalArgumentException("uriTemplate must not be blank");
        }
        this.uriTemplate = uriTemplate;
        this.name = name;
        this.mimeType = mimeType;
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("uriTemplate", uriTemplate);
        value.put("name", name);
        value.put("mimeType", mimeType);
        this.schemaHash = new SchemaHasher().hash(value);
    }
}
