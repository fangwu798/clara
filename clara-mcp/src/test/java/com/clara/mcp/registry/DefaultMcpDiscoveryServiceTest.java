package com.clara.mcp.registry;

import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCodes;
import com.clara.mcp.client.McpClient;
import com.clara.mcp.client.McpInitialization;
import com.clara.mcp.client.McpPage;
import com.clara.mcp.client.McpServerCapabilities;
import com.clara.mcp.client.McpServerIdentity;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import com.clara.mcp.client.McpToolResult;
import com.clara.mcp.client.McpResourceContent;
import com.clara.mcp.client.McpPromptResult;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DefaultMcpDiscoveryServiceTest {
    @Test
    public void refreshCollectsPagesAndPublishesNewSnapshot() {
        InMemoryMcpServerRegistry registry = registry(registration("2025-03-26", "server", "1"));
        InMemoryMcpCapabilityCatalog catalog = new InMemoryMcpCapabilityCatalog();
        FakeClient client = new FakeClient(initialization("2025-03-26", "server", "1"));
        client.pages.add(new McpPage<McpToolDescriptor>(Arrays.asList(tool("one")), "next"));
        client.pages.add(new McpPage<McpToolDescriptor>(Arrays.asList(tool("two")), null));
        DefaultMcpDiscoveryService discovery = service(registry, catalog, client);

        discovery.refresh("tenant", "server-key");

        assertEquals(2, catalog.latest("tenant", "server-key").getTools().size());
        assertEquals(1, catalog.latest("tenant", "server-key").getResourceTemplates().size());
        assertTrue(client.closed);
    }

    @Test
    public void repeatedCursorFailsDeterministically() {
        InMemoryMcpServerRegistry registry = registry(registration("2025-03-26", "server", "1"));
        FakeClient client = new FakeClient(initialization("2025-03-26", "server", "1"));
        client.pages.add(new McpPage<McpToolDescriptor>(Collections.<McpToolDescriptor>emptyList(), "same"));
        client.pages.add(new McpPage<McpToolDescriptor>(Collections.<McpToolDescriptor>emptyList(), "same"));
        try {
            service(registry, new InMemoryMcpCapabilityCatalog(), client)
                    .refresh("tenant", "server-key");
            fail("expected repeated cursor failure");
        } catch (ClaraException exception) {
            assertEquals(ErrorCodes.MCP_PAGINATION_CURSOR_REPEATED, exception.getError().getCode());
        }
    }

    @Test
    public void incompatibleVersionUpdatesRegistrationStatus() {
        InMemoryMcpServerRegistry registry = registry(registration("2025-03-26", "server", "1"));
        FakeClient client = new FakeClient(initialization("2099-01-01", "server", "1"));
        try {
            service(registry, new InMemoryMcpCapabilityCatalog(), client)
                    .refresh("tenant", "server-key");
            fail("expected incompatible protocol");
        } catch (ClaraException exception) {
            assertEquals(ErrorCodes.MCP_PROTOCOL_INCOMPATIBLE, exception.getError().getCode());
            assertEquals(McpRegistrationStatus.INCOMPATIBLE,
                    registry.get("tenant", "server-key").getStatus());
        }
    }

    @Test
    public void changedIdentityRequiresReview() {
        InMemoryMcpServerRegistry registry = registry(registration("2025-03-26", "server", "1"));
        FakeClient client = new FakeClient(initialization("2025-03-26", "other", "1"));
        try {
            service(registry, new InMemoryMcpCapabilityCatalog(), client)
                    .refresh("tenant", "server-key");
            fail("expected identity review");
        } catch (ClaraException exception) {
            assertEquals(ErrorCodes.MCP_SERVER_IDENTITY_CHANGED, exception.getError().getCode());
            assertEquals(McpRegistrationStatus.REVIEW_REQUIRED,
                    registry.get("tenant", "server-key").getStatus());
        }
    }

    private static DefaultMcpDiscoveryService service(InMemoryMcpServerRegistry registry,
            InMemoryMcpCapabilityCatalog catalog, final FakeClient client) {
        return new DefaultMcpDiscoveryService(registry, catalog,
                new McpClientFactory() { public McpClient create(McpServerRegistration ignored) { return client; } },
                Arrays.asList("2025-03-26"), 5,
                new Supplier<String>() { public String get() { return "snapshot-1"; } },
                new LongSupplier() { public long getAsLong() { return 10L; } });
    }
    private static InMemoryMcpServerRegistry registry(McpServerRegistration registration) {
        InMemoryMcpServerRegistry registry = new InMemoryMcpServerRegistry(); registry.register(registration); return registry;
    }
    private static McpServerRegistration registration(String protocol, String name, String version) {
        return new McpServerRegistration("tenant", "server-key", McpTransportProfile.STDIO,
                "server.exe", null, name, version, McpRegistrationStatus.ACTIVE);
    }
    private static McpInitialization initialization(String protocol, String name, String version) {
        return new McpInitialization(protocol, new McpServerIdentity(name, version),
                new McpServerCapabilities(true, true, false, false, false, false));
    }
    private static McpToolDescriptor tool(String name) {
        return new McpToolDescriptor(name, null, Collections.<String, Object>emptyMap());
    }

    private static final class FakeClient implements McpClient {
        private final McpInitialization initialization;
        private final Deque<McpPage<McpToolDescriptor>> pages =
                new ArrayDeque<McpPage<McpToolDescriptor>>();
        private boolean closed;
        private FakeClient(McpInitialization initialization) { this.initialization = initialization; }
        public McpInitialization initialize(List<String> versions) { return initialization; }
        public McpPage<McpToolDescriptor> listTools(String cursor) { return pages.removeFirst(); }
        public McpPage<McpResourceDescriptor> listResources(String cursor) {
            return new McpPage<McpResourceDescriptor>(Collections.<McpResourceDescriptor>emptyList(), null);
        }
        public McpPage<McpResourceTemplateDescriptor> listResourceTemplates(String cursor) {
            return new McpPage<McpResourceTemplateDescriptor>(Arrays.asList(
                    new McpResourceTemplateDescriptor("file:///{path}", "file", "text/plain")), null);
        }
        public McpPage<McpPromptDescriptor> listPrompts(String cursor) {
            return new McpPage<McpPromptDescriptor>(Collections.<McpPromptDescriptor>emptyList(), null);
        }
        public McpToolResult callTool(String name, Map<String, Object> arguments) {
            throw new UnsupportedOperationException();
        }
        public McpResourceContent readResource(String uri) { throw new UnsupportedOperationException(); }
        public McpPromptResult getPrompt(String name, Map<String, Object> arguments) {
            throw new UnsupportedOperationException();
        }
        public void close() throws IOException { closed = true; }
    }
}
