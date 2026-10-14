package com.clara.mcp.tool;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link ExternalToolCatalog} 的单元测试：登记、查找、清空与快照，以及入参校验。
 */
public class ExternalToolCatalogTest {

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
        try {
            snapshot.put("x", new ExternalToolBinding("t", "s", "snap", "hash"));
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            assertTrue(true);
        }
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
