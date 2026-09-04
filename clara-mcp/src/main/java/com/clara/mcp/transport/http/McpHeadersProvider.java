package com.clara.mcp.transport.http;

import java.util.Map;

public interface McpHeadersProvider {
    Map<String, String> getHeaders();
}
