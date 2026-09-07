package com.clara.mcp.transport.http;

import java.util.Map;

/**
 * 定义。
 */
public interface McpHeadersProvider {
    Map<String, String> getHeaders();
}
