package com.clara.mcp.registry;

/**
 * 表示。
 */
public final class McpPromptArgument {
    private final String name;
    private final String description;
    private final boolean required;
    public McpPromptArgument(String name, String description, boolean required) {
        if (name == null || name.trim().isEmpty()) { throw new IllegalArgumentException("name must not be blank"); }
        this.name = name; this.description = description; this.required = required;
    }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public boolean isRequired() { return required; }
}
