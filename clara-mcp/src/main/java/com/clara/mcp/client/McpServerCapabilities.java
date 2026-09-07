package com.clara.mcp.client;

/**
 * 表示。
 */
public final class McpServerCapabilities {
    private final boolean tools;
    private final boolean resources;
    private final boolean prompts;
    private final boolean toolsListChanged;
    private final boolean resourcesListChanged;
    private final boolean promptsListChanged;

    public McpServerCapabilities(boolean tools, boolean resources, boolean prompts,
            boolean toolsListChanged, boolean resourcesListChanged, boolean promptsListChanged) {
        this.tools = tools;
        this.resources = resources;
        this.prompts = prompts;
        this.toolsListChanged = toolsListChanged;
        this.resourcesListChanged = resourcesListChanged;
        this.promptsListChanged = promptsListChanged;
    }
    public boolean supportsTools() { return tools; }
    public boolean supportsResources() { return resources; }
    public boolean supportsPrompts() { return prompts; }
    public boolean supportsToolsListChanged() { return toolsListChanged; }
    public boolean supportsResourcesListChanged() { return resourcesListChanged; }
    public boolean supportsPromptsListChanged() { return promptsListChanged; }
}
