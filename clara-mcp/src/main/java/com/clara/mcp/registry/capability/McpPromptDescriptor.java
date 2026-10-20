package com.clara.mcp.registry.capability;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 描述MCP提示词。
 */
@Getter
public final class McpPromptDescriptor {
    private final String name;
    private final String description;
    private final List<McpPromptArgument> arguments;
    private final String schemaHash;

    public McpPromptDescriptor(String name, String description, List<McpPromptArgument> arguments) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (arguments == null || arguments.contains(null)) {
            throw new IllegalArgumentException("arguments must not be null or contain null");
        }
        this.name = name;
        this.description = description;
        this.arguments = Collections.unmodifiableList(new ArrayList<>(arguments));
        List<Object> values = getObjects(name, description, arguments);
        this.schemaHash = new SchemaHasher().hash(values);
    }

    private static List<Object> getObjects(String name, String description, List<McpPromptArgument> arguments) {
        List<Object> values = new ArrayList<>();
        Map<String, Object> prompt = new LinkedHashMap<>();
        prompt.put("name", name);
        prompt.put("description", description);
        values.add(prompt);
        for (McpPromptArgument argument : arguments) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("name", argument.getName());
            value.put("description", argument.getDescription());
            value.put("required", argument.isRequired());
            values.add(value);
        }
        return values;
    }
}
