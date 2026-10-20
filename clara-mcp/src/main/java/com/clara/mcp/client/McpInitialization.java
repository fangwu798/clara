package com.clara.mcp.client;

import lombok.Getter;

/**
 * 封装MCP的初始化信息。
 */
@Getter
public final class McpInitialization {
    private final String protocolVersion;
    private final McpServerIdentity identity;
    private final McpServerCapabilities capabilities;

    public McpInitialization(String protocolVersion, McpServerIdentity identity,
            McpServerCapabilities capabilities) {
        if (protocolVersion == null || protocolVersion.trim().isEmpty()) {
            throw new IllegalArgumentException("protocolVersion must not be blank");
        }
        if (identity == null) { throw new IllegalArgumentException("identity must not be null"); }
        if (capabilities == null) { throw new IllegalArgumentException("capabilities must not be null"); }
        this.protocolVersion = protocolVersion;
        this.identity = identity;
        this.capabilities = capabilities;
    }
}
