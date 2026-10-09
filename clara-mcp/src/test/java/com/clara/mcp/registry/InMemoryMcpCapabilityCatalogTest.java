package com.clara.mcp.registry;

import com.clara.exception.ClaraException;
import com.clara.exception.ErrorCodes;
import com.clara.mcp.client.McpServerCapabilities;
import com.clara.mcp.client.McpServerIdentity;
import com.clara.mcp.registry.capability.McpPromptArgument;
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link InMemoryMcpCapabilityCatalog} 的发布 / 最新 / 指定快照与能力解析（含 Schema 不一致）测试。
 */
public class InMemoryMcpCapabilityCatalogTest {
    private static final String TENANT = "tenant-1";
    private static final String SERVER = "echo-server";
    private static final String SNAPSHOT = "snapshot-1";
    private static final String VERSION = "2024-11-05";
    private static final McpServerIdentity IDENTITY = new McpServerIdentity("echo", "1.0.0");
    private static final McpServerCapabilities CAPS =
            new McpServerCapabilities(true, true, true, false, false, false);

    private final InMemoryMcpCapabilityCatalog catalog = new InMemoryMcpCapabilityCatalog();

    @Test
    public void publishThenLatestAndGet() {
        McpServerSnapshot snapshot = snapshot(SNAPSHOT, TENANT, SERVER, 1L, tool("echo"));

        catalog.publish(snapshot);

        assertSame(snapshot, catalog.latest(TENANT, SERVER));
        assertSame(snapshot, catalog.get(TENANT, SERVER, SNAPSHOT));
    }

    @Test
    public void missingSnapshotReturnsNull() {
        assertNull(catalog.latest(TENANT, SERVER));
        assertNull(catalog.get(TENANT, SERVER, SNAPSHOT));
    }

    @Test
    public void latestKeepsNewestByDiscoveryTime() {
        McpServerSnapshot older = snapshot("s1", TENANT, SERVER, 1L, tool("echo"));
        McpServerSnapshot newer = snapshot("s2", TENANT, SERVER, 2L, tool("echo"));
        McpServerSnapshot oldest = snapshot("s3", TENANT, SERVER, 0L, tool("echo"));

        catalog.publish(older);
        catalog.publish(newer);
        catalog.publish(oldest);

        assertSame(newer, catalog.latest(TENANT, SERVER));
        assertSame(older, catalog.get(TENANT, SERVER, "s1"));
        assertSame(oldest, catalog.get(TENANT, SERVER, "s3"));
    }

    @Test
    public void publishDuplicateSnapshotIdRejected() {
        catalog.publish(snapshot("s1", TENANT, SERVER, 1L, tool("echo")));

        try {
            catalog.publish(snapshot("s1", TENANT, SERVER, 2L, tool("echo")));
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(true);
        }
    }

    @Test
    public void resolveToolReturnsMatchingDescriptor() {
        McpToolDescriptor tool = tool("echo");
        catalog.publish(snapshot(SNAPSHOT, TENANT, SERVER, 1L, tool));

        McpToolDescriptor resolved = catalog.resolveTool(TENANT, SERVER, SNAPSHOT, "echo",
                tool.getSchemaHash());

        assertSame(tool, resolved);
    }

    @Test
    public void resolveToolRejectsSchemaMismatch() {
        catalog.publish(snapshot(SNAPSHOT, TENANT, SERVER, 1L, tool("echo")));

        assertCode(ErrorCodes.MCP_SCHEMA_MISMATCH, new Runnable() {
            public void run() {
                catalog.resolveTool(TENANT, SERVER, SNAPSHOT, "echo", "deadbeef");
            }
        });
    }

    @Test
    public void resolveToolRejectsUnknownToolAndUnknownSnapshot() {
        catalog.publish(snapshot(SNAPSHOT, TENANT, SERVER, 1L, tool("echo")));

        assertCode(ErrorCodes.MCP_CAPABILITY_NOT_FOUND, new Runnable() {
            public void run() {
                catalog.resolveTool(TENANT, SERVER, SNAPSHOT, "missing", "any-hash");
            }
        });
        assertCode(ErrorCodes.MCP_CAPABILITY_NOT_FOUND, new Runnable() {
            public void run() {
                catalog.resolveTool(TENANT, SERVER, "missing-snapshot", "echo", "any-hash");
            }
        });
    }

    @Test
    public void resolveResourceHitAndMismatch() {
        McpResourceDescriptor resource = new McpResourceDescriptor("file://a.txt", "a", "text/plain");
        McpServerSnapshot snapshot = new McpServerSnapshot(SNAPSHOT, TENANT, SERVER, 1L, VERSION, IDENTITY,
                CAPS, Collections.<McpToolDescriptor>emptyList(), Collections.singletonList(resource),
                Collections.<McpResourceTemplateDescriptor>emptyList(),
                Collections.<McpPromptDescriptor>emptyList(), McpHealthStatus.HEALTHY);
        catalog.publish(snapshot);

        assertSame(resource, catalog.resolveResource(TENANT, SERVER, SNAPSHOT, "file://a.txt",
                resource.getSchemaHash()));
        assertCode(ErrorCodes.MCP_SCHEMA_MISMATCH, new Runnable() {
            public void run() {
                catalog.resolveResource(TENANT, SERVER, SNAPSHOT, "file://a.txt", "bad");
            }
        });
    }

    @Test
    public void resolvePromptHitAndMissing() {
        McpPromptDescriptor prompt = new McpPromptDescriptor("greet", "g", Collections.<McpPromptArgument>emptyList());
        McpServerSnapshot snapshot = new McpServerSnapshot(SNAPSHOT, TENANT, SERVER, 1L, VERSION, IDENTITY,
                CAPS, Collections.<McpToolDescriptor>emptyList(),
                Collections.<McpResourceDescriptor>emptyList(),
                Collections.<McpResourceTemplateDescriptor>emptyList(),
                Collections.singletonList(prompt), McpHealthStatus.HEALTHY);
        catalog.publish(snapshot);

        assertSame(prompt, catalog.resolvePrompt(TENANT, SERVER, SNAPSHOT, "greet", prompt.getSchemaHash()));
        assertCode(ErrorCodes.MCP_CAPABILITY_NOT_FOUND, new Runnable() {
            public void run() {
                catalog.resolvePrompt(TENANT, SERVER, SNAPSHOT, "missing", "any");
            }
        });
    }

    @Test
    public void blankArgumentsRejected() {
        assertIllegalArgument(new Runnable() {
            public void run() {
                catalog.resolveTool(" ", SERVER, SNAPSHOT, "echo", "hash");
            }
        });
        assertIllegalArgument(new Runnable() {
            public void run() {
                catalog.resolveTool(TENANT, SERVER, SNAPSHOT, "echo", " ");
            }
        });
    }

    private static McpServerSnapshot snapshot(String snapshotId, String tenantId, String serverKey,
            long discoveredAt, McpToolDescriptor tool) {
        List<McpToolDescriptor> tools = new ArrayList<McpToolDescriptor>();
        tools.add(tool);
        return new McpServerSnapshot(snapshotId, tenantId, serverKey, discoveredAt, VERSION, IDENTITY, CAPS,
                tools, Collections.<McpResourceDescriptor>emptyList(),
                Collections.<McpResourceTemplateDescriptor>emptyList(),
                Collections.<McpPromptDescriptor>emptyList(), McpHealthStatus.HEALTHY);
    }

    private static McpToolDescriptor tool(String name) {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        return new McpToolDescriptor(name, "echo tool", schema);
    }

    private static void assertIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(true);
        }
    }

    private static void assertCode(String expectedCode, Runnable action) {
        try {
            action.run();
            fail("expected ClaraException with code " + expectedCode);
        } catch (ClaraException exception) {
            assertEquals(expectedCode, exception.getError().getCode());
        }
    }
}
