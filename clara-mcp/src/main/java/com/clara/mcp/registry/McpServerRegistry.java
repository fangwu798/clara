package com.clara.mcp.registry;

import java.util.List;

/**
 * 负责注册并查找MCP服务端。
 */
public interface McpServerRegistry {
    void register(McpServerRegistration registration);
    void updateStatus(String tenantId, String serverKey, McpRegistrationStatus status);
    List<McpServerRegistration> listEnabled(String tenantId);
    McpServerRegistration get(String tenantId, String serverKey);
}
