package com.clara.mcp.tool;

import com.clara.mcp.client.McpServerCapabilities;
import com.clara.mcp.client.McpServerIdentity;
import com.clara.mcp.registry.McpHealthStatus;
import com.clara.mcp.registry.McpServerSnapshot;
import com.clara.mcp.registry.capability.McpPromptDescriptor;
import com.clara.mcp.registry.capability.McpResourceDescriptor;
import com.clara.mcp.registry.capability.McpResourceTemplateDescriptor;
import com.clara.mcp.registry.capability.McpToolDescriptor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link ExternalToolCatalog} 的单元测试：登记、查找、清空与快照，以及入参校验。
 */
public class ExternalToolCatalogTest {

    private static final String TENANT = "tenant-1";
    private static final String SERVER = "echo-server";
    private static final String VERSION = "2024-11-05";

    @Test
    public void registerFindSnapshotAndClear() {
        ExternalToolCatalog catalog = new ExternalToolCatalog();
        assertNull(catalog.find("missing"));

        ExternalToolBinding binding = new ExternalToolBinding("tenant-a", "server-a", "snap-1", "hash-1");
        catalog.register("remote.tool", binding);

        assertSame(binding, catalog.find("remote.tool"));
        assertNull(catalog.find(null));
        Map<String, ExternalToolBinding> snapshot = catalog.snapshot();
        assertEquals(1, snapshot.size());
        assertSame(binding, snapshot.get("remote.tool"));

        catalog.clear();
        assertNull(catalog.find("remote.tool"));
        assertTrue(catalog.snapshot().isEmpty());
    }

    @Test
    public void snapshotIsImmutableCopy() {
        ExternalToolCatalog catalog = new ExternalToolCatalog();
        catalog.register("remote.tool", new ExternalToolBinding("t", "s", "snap", "hash"));
        Map<String, ExternalToolBinding> snapshot = catalog.snapshot();
        ExternalToolBinding extra = new ExternalToolBinding("t", "s", "snap", "hash");
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("x", extra));
    }

    @Test
    public void rejectsInvalidRegistrationArguments() {
        ExternalToolCatalog catalog = new ExternalToolCatalog();
        ExternalToolBinding binding = new ExternalToolBinding("t", "s", "snap", "hash");
        try {
            catalog.register("  ", binding);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(true);
        }
        try {
            catalog.register("remote.tool", null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(true);
        }
    }

    @Test
    public void bindingRejectsBlankFields() {
        assertBindingRejected(null, "s", "snap", "hash");
        assertBindingRejected("t", " ", "snap", "hash");
        assertBindingRejected("t", "s", null, "hash");
        assertBindingRejected("t", "s", "snap", " ");
    }

    @Test
    public void registerSnapshotRegistersAllToolsWithBindingFields() {
        ExternalToolCatalog catalog = new ExternalToolCatalog();
        McpServerSnapshot snapshot = snapshot("snap-1", tool("echo"), tool("sum"));

        int count = catalog.registerSnapshot(TENANT, snapshot);

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

        int count = catalog.registerSnapshot(TENANT, snapshot("snap-1"));

        assertEquals(0, count);
        assertTrue(catalog.snapshot().isEmpty());
    }

    @Test
    public void registerSnapshotRejectsInvalidArguments() {
        ExternalToolCatalog catalog = new ExternalToolCatalog();
        McpServerSnapshot snapshot = snapshot("snap-1");
        assertSnapshotRejected(null, snapshot, catalog);
        assertSnapshotRejected("  ", snapshot, catalog);
        assertSnapshotRejected(TENANT, null, catalog);
    }

    private static void assertSnapshotRejected(String tenantId, McpServerSnapshot snapshot,
            ExternalToolCatalog catalog) {
        try {
            catalog.registerSnapshot(tenantId, snapshot);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(true);
        }
    }

    private static McpToolDescriptor tool(String name) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        return new McpToolDescriptor(name, name + " tool", schema);
    }

    private static McpServerSnapshot snapshot(String snapshotId, McpToolDescriptor... tools) {
        List<McpToolDescriptor> values = new ArrayList<>();
        Collections.addAll(values, tools);
        return new McpServerSnapshot(snapshotId, TENANT, SERVER, 1L, VERSION,
                new McpServerIdentity("echo", "1.0.0"),
                new McpServerCapabilities(true, false, false, false, false, false),
                values, Collections.<McpResourceDescriptor>emptyList(),
                Collections.<McpResourceTemplateDescriptor>emptyList(),
                Collections.<McpPromptDescriptor>emptyList(), McpHealthStatus.HEALTHY);
    }

    private static void assertBindingRejected(String tenantId, String serverKey, String snapshotId,
            String schemaHash) {
        try {
            new ExternalToolBinding(tenantId, serverKey, snapshotId, schemaHash);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(true);
        }
    }
}
