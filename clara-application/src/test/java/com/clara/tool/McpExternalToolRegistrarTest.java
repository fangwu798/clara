package com.clara.tool;

import com.clara.mcp.client.McpServerCapabilities;
import com.clara.mcp.client.McpServerIdentity;
import com.clara.mcp.registry.McpHealthStatus;
import com.clara.mcp.registry.McpServerSnapshot;
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link McpExternalToolRegistrar} 的单元测试：把快照工具登记为路由绑定的字段正确性、空快照与入参校验。
 */
public class McpExternalToolRegistrarTest {

    private static final String TENANT = "tenant-1";
    private static final String SERVER = "echo-server";
    private static final String VERSION = "2024-11-05";

    @Test
    public void registersAllToolsWithBindingFields() {
        ExternalToolCatalog catalog = new ExternalToolCatalog();
        McpServerSnapshot snapshot = snapshot("snap-1", tool("echo"), tool("sum"));

        int count = new McpExternalToolRegistrar().register(TENANT, snapshot, catalog);

        assertEquals(2, count);
        ExternalToolBinding echo = catalog.find("echo");
        assertNotNull(echo);
        assertEquals(TENANT, echo.getTenantId());
        assertEquals(SERVER, echo.getServerKey());
        assertEquals("snap-1", echo.getSnapshotId());
        assertEquals(snapshot.getTools().get(0).getSchemaHash(), echo.getSchemaHash());
        assertNotNull(catalog.find("sum"));
    }

    @Test
    public void emptySnapshotRegistersNothing() {
        ExternalToolCatalog catalog = new ExternalToolCatalog();

        int count = new McpExternalToolRegistrar().register(TENANT, snapshot("snap-1"), catalog);

        assertEquals(0, count);
        assertTrue(catalog.snapshot().isEmpty());
    }

    @Test
    public void rejectsInvalidArguments() {
        ExternalToolCatalog catalog = new ExternalToolCatalog();
        McpServerSnapshot snapshot = snapshot("snap-1");
        assertRejected(null, snapshot, catalog);
        assertRejected("  ", snapshot, catalog);
        assertRejected(TENANT, null, catalog);
        assertRejected(TENANT, snapshot, null);
    }

    private static void assertRejected(String tenantId, McpServerSnapshot snapshot,
            ExternalToolCatalog catalog) {
        try {
            new McpExternalToolRegistrar().register(tenantId, snapshot, catalog);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(true);
        }
    }

    private static McpToolDescriptor tool(String name) {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        return new McpToolDescriptor(name, name + " tool", schema);
    }

    private static McpServerSnapshot snapshot(String snapshotId, McpToolDescriptor... tools) {
        List<McpToolDescriptor> values = new ArrayList<McpToolDescriptor>();
        Collections.addAll(values, tools);
        return new McpServerSnapshot(snapshotId, TENANT, SERVER, 1L, VERSION,
                new McpServerIdentity("echo", "1.0.0"),
                new McpServerCapabilities(true, false, false, false, false, false),
                values, Collections.<McpResourceDescriptor>emptyList(),
                Collections.<McpResourceTemplateDescriptor>emptyList(),
                Collections.<McpPromptDescriptor>emptyList(), McpHealthStatus.HEALTHY);
    }
}
