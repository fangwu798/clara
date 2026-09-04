package com.clara.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ToolSpecification {
    private final String name;
    private final String description;
    private final Map<String, Object> inputSchema;
    public ToolSpecification(String name, String description, Map<String, Object> inputSchema) {
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("name must not be blank");
        if (inputSchema == null) throw new IllegalArgumentException("inputSchema must not be null");
        this.name = name;
        this.description = description;
        this.inputSchema = Collections.unmodifiableMap(
                new LinkedHashMap<String, Object>(inputSchema));
    }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Map<String, Object> getInputSchema() { return inputSchema; }
}
