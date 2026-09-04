package com.clara.mcp.registry;

import java.util.List;

public interface McpServerRegistry {
    void register(McpServerRegistration registration);
    void updateStatus(String tenantId, String serverKey, McpRegistrationStatus status);
    List<McpServerRegistration> listEnabled(String tenantId);
    McpServerRegistration get(String tenantId, String serverKey);
}
