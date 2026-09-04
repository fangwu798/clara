package com.clara.mcp.client;

import com.clara.mcp.registry.ImmutableValues;
import java.util.List;
import java.util.Map;

public final class McpToolResult {
    private final List<Map<String, Object>> content;
    private final boolean error;
    public McpToolResult(List<Map<String, Object>> content, boolean error) {
        this.content = ImmutableValues.copyMapList(content); this.error = error;
    }
    public List<Map<String, Object>> getContent() { return content; }
    public boolean isError() { return error; }
}
