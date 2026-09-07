package com.clara.mcp.client;

import java.util.Objects;

/**
 * 标识MCP服务端。
 */
public final class McpServerIdentity {
    private final String name;
    private final String version;
    public McpServerIdentity(String name, String version) {
        if (name == null || name.trim().isEmpty()) { throw new IllegalArgumentException("name must not be blank"); }
        this.name = name;
        this.version = version;
    }
    public String getName() { return name; }
    public String getVersion() { return version; }
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof McpServerIdentity)) return false;
        McpServerIdentity that = (McpServerIdentity) other;
        return name.equals(that.name) && Objects.equals(version, that.version);
    }
    @Override public int hashCode() { return Objects.hash(name, version); }
}
