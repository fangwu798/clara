package com.clara.mcp.client;

public final class McpInitialization {
    private final String protocolVersion;
    private final McpServerIdentity identity;
    private final McpServerCapabilities capabilities;

    public McpInitialization(String protocolVersion, McpServerIdentity identity,
            McpServerCapabilities capabilities) {
        if (protocolVersion == null || protocolVersion.trim().isEmpty()) {
            throw new IllegalArgumentException("protocolVersion must not be blank");
        }
        if (identity == null) throw new IllegalArgumentException("identity must not be null");
        if (capabilities == null) throw new IllegalArgumentException("capabilities must not be null");
        this.protocolVersion = protocolVersion;
        this.identity = identity;
        this.capabilities = capabilities;
    }
    public String getProtocolVersion() { return protocolVersion; }
    public McpServerIdentity getIdentity() { return identity; }
    public McpServerCapabilities getCapabilities() { return capabilities; }
}
