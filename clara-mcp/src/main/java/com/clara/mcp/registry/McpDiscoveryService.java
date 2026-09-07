package com.clara.mcp.registry;

/**
 * 提供MCP发现相关的应用服务。
 */
public interface McpDiscoveryService {
    McpServerSnapshot discover(McpServerRegistration registration);
    void refresh(String tenantId, String serverKey);
}
