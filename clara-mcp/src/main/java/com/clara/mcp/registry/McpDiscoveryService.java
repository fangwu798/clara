package com.clara.mcp.registry;

public interface McpDiscoveryService {
    McpServerSnapshot discover(McpServerRegistration registration);
    void refresh(String tenantId, String serverKey);
}
