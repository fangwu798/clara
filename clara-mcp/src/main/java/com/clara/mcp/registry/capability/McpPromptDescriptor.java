package com.clara.mcp.registry.capability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 描述MCP提示词。
 */
public final class McpPromptDescriptor {
    private final String name;
    private final String description;
    private final List<McpPromptArgument> arguments;
    private final String schemaHash;
    public McpPromptDescriptor(String name, String description, List<McpPromptArgument> arguments) {
        if (name == null || name.trim().isEmpty()) { throw new IllegalArgumentException("name must not be blank"); }
        if (arguments == null || arguments.contains(null)) {
            throw new IllegalArgumentException("arguments must not be null or contain null");
        }
        this.name = name; this.description = description;
        this.arguments = Collections.unmodifiableList(new ArrayList<McpPromptArgument>(arguments));
        List<Object> values = new ArrayList<Object>();
        Map<String, Object> prompt = new LinkedHashMap<String, Object>();
        prompt.put("name", name); prompt.put("description", description);
        values.add(prompt);
        for (McpPromptArgument argument : arguments) {
            Map<String, Object> value = new LinkedHashMap<String, Object>();
            value.put("name", argument.getName()); value.put("description", argument.getDescription());
            value.put("required", argument.isRequired());
            values.add(value);
        }
        this.schemaHash = new SchemaHasher().hash(values);
    }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public List<McpPromptArgument> getArguments() { return arguments; }
    public String getSchemaHash() { return schemaHash; }
}
