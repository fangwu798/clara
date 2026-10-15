package com.clara.tool;

import com.clara.dto.tool.ToolContext;
import com.clara.mcp.client.McpServerCapabilities;
import com.clara.mcp.client.McpServerIdentity;
import com.clara.mcp.registry.InMemoryMcpCapabilityCatalog;
import com.clara.mcp.registry.InMemoryMcpServerRegistry;
import com.clara.mcp.registry.McpHealthStatus;
import com.clara.mcp.registry.McpRegistrationStatus;
import com.clara.mcp.registry.McpServerRegistration;
import com.clara.mcp.registry.McpServerSnapshot;
import com.clara.mcp.registry.McpTransportProfile;
import com.clara.mcp.registry.capability.McpPromptDescriptor;
import com.clara.mcp.registry.capability.McpResourceDescriptor;
import com.clara.mcp.registry.capability.McpResourceTemplateDescriptor;
import com.clara.mcp.registry.capability.McpToolDescriptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link McpToolCatalog} 的单元测试：把已发现快照转成下发给模型的工具声明，以及无快照 / 跨租户与入参校验。
 */
public class McpToolCatalogTest {

    private static final String TENANT = "local";
    private static final String SERVER = "codebase-memory-mcp";
    private static final String VERSION = "2024-11-05";

    @Test
    public void mapsSnapshotToolsToDeclarations() {
        InMemoryMcpServerRegistry registry = new InMemoryMcpServerRegistry();
        InMemoryMcpCapabilityCatalog catalog = new InMemoryMcpCapabilityCatalog();
        registry.register(registration(TENANT, SERVER));
        catalog.publish(snapshot(TENANT, SERVER, "snap-1", tool("search_graph"), tool("get_file_outline")));

        List<ToolContext> tools = new McpToolCatalog(registry, catalog, TENANT).availableTools();

        assertEquals(2, tools.size());
        assertEquals("search_graph", tools.get(0).getFunction().getName());
        assertEquals("search_graph tool", tools.get(0).getFunction().getDescription());
        assertEquals("object", tools.get(0).getFunction().getParameters().get("type"));
        assertEquals("get_file_outline", tools.get(1).getFunction().getName());
    }

    @Test
    public void skipsServersWithoutSnapshotAndOtherTenants() {
        InMemoryMcpServerRegistry registry = new InMemoryMcpServerRegistry();
        InMemoryMcpCapabilityCatalog catalog = new InMemoryMcpCapabilityCatalog();
        registry.register(registration(TENANT, SERVER));
        catalog.publish(snapshot("other-tenant", SERVER, "snap-1", tool("search_graph")));

        assertTrue(new McpToolCatalog(registry, catalog, TENANT).availableTools().isEmpty());

        catalog.publish(snapshot(TENANT, SERVER, "snap-2", tool("search_graph")));
        assertEquals(1, new McpToolCatalog(registry, catalog, TENANT).availableTools().size());
    }

    @Test
    public void emptySnapshotYieldsNoTool() {
        InMemoryMcpServerRegistry registry = new InMemoryMcpServerRegistry();
        InMemoryMcpCapabilityCatalog catalog = new InMemoryMcpCapabilityCatalog();
        registry.register(registration(TENANT, SERVER));
        catalog.publish(snapshot(TENANT, SERVER, "snap-1"));

        assertTrue(new McpToolCatalog(registry, catalog, TENANT).availableTools().isEmpty());
    }

    @Test
    public void rejectsInvalidArguments() {
        InMemoryMcpServerRegistry registry = new InMemoryMcpServerRegistry();
        InMemoryMcpCapabilityCatalog catalog = new InMemoryMcpCapabilityCatalog();
        assertRejected(null, catalog, TENANT);
        assertRejected(registry, null, TENANT);
        assertRejected(registry, catalog, null);
        assertRejected(registry, catalog, "  ");
    }

    private static void assertRejected(InMemoryMcpServerRegistry registry,
            InMemoryMcpCapabilityCatalog catalog, String tenantId) {
        try {
            new McpToolCatalog(registry, catalog, tenantId);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(true);
        }
    }

    private static McpServerRegistration registration(String tenantId, String serverKey) {
        return new McpServerRegistration(tenantId, serverKey, McpTransportProfile.STDIO,
                "codebase-memory-mcp", null, null, null, McpRegistrationStatus.ACTIVE);
    }

    private static McpToolDescriptor tool(String name) {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        return new McpToolDescriptor(name, name + " tool", schema);
    }

    private static McpServerSnapshot snapshot(String tenantId, String serverKey, String snapshotId,
            McpToolDescriptor... tools) {
        List<McpToolDescriptor> values = new ArrayList<McpToolDescriptor>();
        Collections.addAll(values, tools);
        return new McpServerSnapshot(snapshotId, tenantId, serverKey, 1L, VERSION,
                new McpServerIdentity(SERVER, "0.11.0"),
                new McpServerCapabilities(true, false, false, false, false, false),
                values, Collections.<McpResourceDescriptor>emptyList(),
                Collections.<McpResourceTemplateDescriptor>emptyList(),
                Collections.<McpPromptDescriptor>emptyList(), McpHealthStatus.HEALTHY);
    }
}