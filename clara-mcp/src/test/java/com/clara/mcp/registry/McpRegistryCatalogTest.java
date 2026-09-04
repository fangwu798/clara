package com.clara.mcp.registry;

import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCodes;
import com.clara.mcp.client.McpServerCapabilities;
import com.clara.mcp.client.McpServerIdentity;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class McpRegistryCatalogTest {
    @Test
    public void registryFiltersByTenantAndStatus() {
        InMemoryMcpServerRegistry registry = new InMemoryMcpServerRegistry();
        registry.register(registration("tenant-a", "alpha", McpRegistrationStatus.ACTIVE));
        registry.register(registration("tenant-a", "disabled", McpRegistrationStatus.DISABLED));
        registry.register(registration("tenant-b", "beta", McpRegistrationStatus.ACTIVE));
        assertEquals(1, registry.listEnabled("tenant-a").size());
        assertEquals("alpha", registry.listEnabled("tenant-a").get(0).getServerKey());
    }

    @Test
    public void catalogRetainsSnapshotsAndPinsToolSchema() {
        InMemoryMcpCapabilityCatalog catalog = new InMemoryMcpCapabilityCatalog();
        McpToolDescriptor oldTool = new McpToolDescriptor("search", null,
                Collections.<String, Object>singletonMap("type", "object"));
        McpToolDescriptor newTool = new McpToolDescriptor("search", null,
                Collections.<String, Object>singletonMap("type", "string"));
        McpServerSnapshot oldSnapshot = snapshot("old", oldTool);
        McpServerSnapshot newSnapshot = snapshot("new", newTool);
        catalog.publish(oldSnapshot); catalog.publish(newSnapshot);

        assertSame(newSnapshot, catalog.latest("tenant", "server"));
        assertSame(oldSnapshot, catalog.get("tenant", "server", "old"));
        assertSame(oldTool, catalog.resolveTool("tenant", "server", "old", "search",
                oldTool.getSchemaHash()));
        try {
            catalog.resolveTool("tenant", "server", "new", "search", oldTool.getSchemaHash());
            fail("expected schema change");
        } catch (ClaraException exception) {
            assertEquals(ErrorCodes.MCP_CAPABILITY_CHANGED, exception.getError().getCode());
        }
    }

    private static McpServerSnapshot snapshot(String id, McpToolDescriptor tool) {
        return new McpServerSnapshot(id, "tenant", "server", 1L, "2025-03-26",
                new McpServerIdentity("test", "1"),
                new McpServerCapabilities(true, false, false, false, false, false),
                Arrays.asList(tool), Collections.<McpResourceDescriptor>emptyList(),
                Collections.<McpResourceTemplateDescriptor>emptyList(),
                Collections.<McpPromptDescriptor>emptyList(), McpHealthStatus.HEALTHY);
    }
    private static McpServerRegistration registration(String tenant, String server,
            McpRegistrationStatus status) {
        return new McpServerRegistration(tenant, server, McpTransportProfile.STDIO,
                "server.exe", null, null, null, status);
    }
}
