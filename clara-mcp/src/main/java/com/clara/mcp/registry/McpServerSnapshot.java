package com.clara.mcp.registry;

import com.clara.mcp.client.McpServerCapabilities;
import com.clara.mcp.client.McpServerIdentity;
import com.clara.mcp.registry.capability.McpPromptDescriptor;
import com.clara.mcp.registry.capability.McpResourceDescriptor;
import com.clara.mcp.registry.capability.McpResourceTemplateDescriptor;
import com.clara.mcp.registry.capability.McpToolDescriptor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 保存MCP服务端在特定时刻的快照。
 */
public final class McpServerSnapshot {
    private final String snapshotId;
    private final String tenantId;
    private final String serverKey;
    private final long discoveredAtEpochMillis;
    private final String protocolVersion;
    private final McpServerIdentity identity;
    private final McpServerCapabilities capabilities;
    private final List<McpToolDescriptor> tools;
    private final List<McpResourceDescriptor> resources;
    private final List<McpResourceTemplateDescriptor> resourceTemplates;
    private final List<McpPromptDescriptor> prompts;
    private final McpHealthStatus healthStatus;

    public McpServerSnapshot(String snapshotId, String tenantId, String serverKey,
            long discoveredAtEpochMillis, String protocolVersion, McpServerIdentity identity,
            McpServerCapabilities capabilities, List<McpToolDescriptor> tools,
            List<McpResourceDescriptor> resources, List<McpResourceTemplateDescriptor> resourceTemplates,
            List<McpPromptDescriptor> prompts,
            McpHealthStatus healthStatus) {
        require(snapshotId, "snapshotId"); require(tenantId, "tenantId"); require(serverKey, "serverKey");
        if (discoveredAtEpochMillis < 0L) { throw new IllegalArgumentException("discoveredAt must not be negative"); }
        require(protocolVersion, "protocolVersion");
        if (identity == null || capabilities == null || tools == null || resources == null
                || resourceTemplates == null || prompts == null || healthStatus == null) {
            throw new IllegalArgumentException("snapshot fields must not be null");
        }
        uniqueToolNames(tools); uniqueResourceUris(resources);
        uniqueResourceTemplateUris(resourceTemplates); uniquePromptNames(prompts);
        this.snapshotId = snapshotId; this.tenantId = tenantId; this.serverKey = serverKey;
        this.discoveredAtEpochMillis = discoveredAtEpochMillis;
        this.protocolVersion = protocolVersion; this.identity = identity;
        this.capabilities = capabilities;
        this.tools = immutable(tools); this.resources = immutable(resources);
        this.resourceTemplates = immutable(resourceTemplates); this.prompts = immutable(prompts);
        this.healthStatus = healthStatus;
    }
    public String getSnapshotId() { return snapshotId; }
    public String getTenantId() { return tenantId; }
    public String getServerKey() { return serverKey; }
    public long getDiscoveredAtEpochMillis() { return discoveredAtEpochMillis; }
    public String getProtocolVersion() { return protocolVersion; }
    public McpServerIdentity getIdentity() { return identity; }
    public McpServerCapabilities getCapabilities() { return capabilities; }
    public List<McpToolDescriptor> getTools() { return tools; }
    public List<McpResourceDescriptor> getResources() { return resources; }
    public List<McpResourceTemplateDescriptor> getResourceTemplates() { return resourceTemplates; }
    public List<McpPromptDescriptor> getPrompts() { return prompts; }
    public McpHealthStatus getHealthStatus() { return healthStatus; }
    private static <T> List<T> immutable(List<T> values) {
        if (values.contains(null)) { throw new IllegalArgumentException("capabilities must not contain null"); }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
    private static void uniqueToolNames(List<McpToolDescriptor> values) {
        Set<String> names = new HashSet<>();
        for (McpToolDescriptor value : values) if (value == null || !names.add(value.getName())) { throw duplicate(); }
    }
    private static void uniqueResourceUris(List<McpResourceDescriptor> values) {
        Set<String> names = new HashSet<>();
        for (McpResourceDescriptor value : values) if (value == null || !names.add(value.getUri())) { throw duplicate(); }
    }
    private static void uniquePromptNames(List<McpPromptDescriptor> values) {
        Set<String> names = new HashSet<>();
        for (McpPromptDescriptor value : values) if (value == null || !names.add(value.getName())) { throw duplicate(); }
    }
    private static void uniqueResourceTemplateUris(List<McpResourceTemplateDescriptor> values) {
        Set<String> names = new HashSet<>();
        for (McpResourceTemplateDescriptor value : values) {
            if (value == null || !names.add(value.getUriTemplate())) { throw duplicate(); }
        }
    }
    private static IllegalArgumentException duplicate() { return new IllegalArgumentException("duplicate capability identifier"); }
    private static void require(String value, String field) {
        if (value == null || value.trim().isEmpty()) { throw new IllegalArgumentException(field + " must not be blank"); }
    }
}
