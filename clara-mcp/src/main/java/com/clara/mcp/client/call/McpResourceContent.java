package com.clara.mcp.client.call;

import com.clara.mcp.registry.ImmutableValues;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * 表示MCP资源携带的内容。
 */
@Getter
public final class McpResourceContent {
    private final List<Map<String, Object>> content;
    public McpResourceContent(List<Map<String, Object>> content) {
        this.content = ImmutableValues.copyMapList(content);
    }
}
