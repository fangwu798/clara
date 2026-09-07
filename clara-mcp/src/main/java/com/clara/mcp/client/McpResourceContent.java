package com.clara.mcp.client;

import com.clara.mcp.registry.ImmutableValues;
import java.util.List;
import java.util.Map;

/**
 * 表示MCP资源携带的内容。
 */
public final class McpResourceContent {
    private final List<Map<String, Object>> content;
    public McpResourceContent(List<Map<String, Object>> content) {
        this.content = ImmutableValues.copyMapList(content);
    }
    public List<Map<String, Object>> getContent() { return content; }
}
