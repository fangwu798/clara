package com.clara.mcp.registry;

import com.clara.mcp.client.McpClient;

public interface McpClientFactory {
    McpClient create(McpServerRegistration registration);
}
