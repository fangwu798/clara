package com.clara.mcp.registry;

import com.clara.exception.ClaraException;
import com.clara.exception.ErrorCodes;
import com.clara.mcp.client.McpClient;
import com.clara.mcp.client.McpInitialization;
import com.clara.mcp.client.McpPage;
import com.clara.mcp.client.call.McpPromptRequest;
import com.clara.mcp.client.call.McpPromptResult;
import com.clara.mcp.client.call.McpResourceContent;
import com.clara.mcp.client.call.McpResourceRequest;
import com.clara.mcp.client.McpServerCapabilities;
import com.clara.mcp.client.McpServerIdentity;
import com.clara.mcp.client.McpClientFactory;
import com.clara.mcp.registry.capability.McpPromptDescriptor;
import com.clara.mcp.registry.capability.McpResourceDescriptor;
import com.clara.mcp.registry.capability.McpResourceTemplateDescriptor;
import com.clara.mcp.registry.capability.McpToolDescriptor;
import com.clara.mcp.client.call.McpToolCall;
import com.clara.mcp.client.call.McpToolResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link DefaultMcpDiscoveryService} 的行为测试：用假客户端返回能力与分页，不依赖真实服务端与网络。
 */
public class DefaultMcpDiscoveryServiceTest {
    private static final String TENANT = "tenant-1";
    private static final String SERVER = "echo-server";
    private static final String VERSION = "2024-11-05";
    private static final McpServerIdentity IDENTITY = new McpServerIdentity("echo", "1.0.0");
    private static final McpServerCapabilities ALL =
            new McpServerCapabilities(true, true, false, false, false, false);

    @Test
    public void discoverBuildsSnapshotAndClosesClient() {
        FakeClient client = new FakeClient(new McpInitialization(VERSION, IDENTITY, ALL));
        client.toolPages.add(new McpPage<McpToolDescriptor>(tools(descriptor("echo")), null));
        client.resources.add(new McpResourceDescriptor("file://a.txt", "a", "text/plain"));
        client.templates.add(new McpResourceTemplateDescriptor("file://{name}", "t", "text/plain"));
        DefaultMcpDiscoveryService service = service(new FakeClientFactory(client), 10);

        McpServerSnapshot snapshot = service.discover(registration(IDENTITY.getName(), IDENTITY.getVersion()));

        assertEquals(TENANT, snapshot.getTenantId());
        assertEquals(SERVER, snapshot.getServerKey());
        assertEquals(VERSION, snapshot.getProtocolVersion());
        assertEquals(1, snapshot.getTools().size());
        assertEquals(1, snapshot.getResources().size());
        assertEquals(1, snapshot.getResourceTemplates().size());
        assertEquals(McpHealthStatus.HEALTHY, snapshot.getHealthStatus());
        assertEquals(1, client.closeCount);
    }

    @Test
    public void discoverThrowsWhenVersionIncompatibleAndStillClosesClient() {
        FakeClient client = new FakeClient(new McpInitialization("1999-01-01", IDENTITY, ALL));
        DefaultMcpDiscoveryService service = service(new FakeClientFactory(client), 10);

        assertCode(ErrorCodes.MCP_PROTOCOL_INCOMPATIBLE, new Runnable() {
            public void run() {
                service.discover(registration(IDENTITY.getName(), IDENTITY.getVersion()));
            }
        });
        assertEquals(1, client.closeCount);
    }

    @Test
    public void discoverThrowsWhenIdentityChanged() {
        FakeClient client = new FakeClient(new McpInitialization(VERSION, IDENTITY, ALL));
        DefaultMcpDiscoveryService service = service(new FakeClientFactory(client), 10);

        assertCode(ErrorCodes.MCP_SERVER_IDENTITY_CHANGED, new Runnable() {
            public void run() {
                service.discover(registration("other-name", IDENTITY.getVersion()));
            }
        });
        assertEquals(1, client.closeCount);
    }

    @Test
    public void discoverThrowsWhenPaginationLimitExceeded() {
        FakeClient client = new FakeClient(new McpInitialization(VERSION, IDENTITY, ALL));
        client.toolPages.add(new McpPage<McpToolDescriptor>(Collections.<McpToolDescriptor>emptyList(), "c1"));
        client.toolPages.add(new McpPage<McpToolDescriptor>(Collections.<McpToolDescriptor>emptyList(), "c2"));
        client.toolPages.add(new McpPage<McpToolDescriptor>(Collections.<McpToolDescriptor>emptyList(), "c3"));
        DefaultMcpDiscoveryService service = service(new FakeClientFactory(client), 2);

        assertCode(ErrorCodes.MCP_PAGINATION_LIMIT_EXCEEDED, new Runnable() {
            public void run() {
                service.discover(registration(IDENTITY.getName(), IDENTITY.getVersion()));
            }
        });
    }

    @Test
    public void discoverThrowsWhenCursorRepeats() {
        FakeClient client = new FakeClient(new McpInitialization(VERSION, IDENTITY, ALL));
        client.toolPages.add(new McpPage<McpToolDescriptor>(Collections.<McpToolDescriptor>emptyList(), "c1"));
        client.toolPages.add(new McpPage<McpToolDescriptor>(Collections.<McpToolDescriptor>emptyList(), "c1"));
        DefaultMcpDiscoveryService service = service(new FakeClientFactory(client), 10);

        assertCode(ErrorCodes.MCP_PAGINATION_CURSOR_REPEATED, new Runnable() {
            public void run() {
                service.discover(registration(IDENTITY.getName(), IDENTITY.getVersion()));
            }
        });
    }

    @Test
    public void refreshPublishesSnapshotAndActivatesRegistration() {
        FakeClient client = new FakeClient(new McpInitialization(VERSION, IDENTITY, ALL));
        client.toolPages.add(new McpPage<McpToolDescriptor>(tools(descriptor("echo")), null));
        FakeRegistry registry = new FakeRegistry(registration(IDENTITY.getName(), IDENTITY.getVersion()));
        InMemoryMcpCapabilityCatalog catalog = new InMemoryMcpCapabilityCatalog();
        DefaultMcpDiscoveryService service = new DefaultMcpDiscoveryService(registry, catalog,
                new FakeClientFactory(client), Arrays.asList(VERSION), 10);

        service.refresh(TENANT, SERVER);

        assertSame(McpRegistrationStatus.ACTIVE, registry.status);
        assertEquals(1, catalog.latest(TENANT, SERVER).getTools().size());
        assertEquals(1, client.closeCount);
    }

    @Test
    public void refreshMarksIncompatibleOnProtocolMismatch() {
        FakeClient client = new FakeClient(new McpInitialization("1999-01-01", IDENTITY, ALL));
        FakeRegistry registry = new FakeRegistry(registration(IDENTITY.getName(), IDENTITY.getVersion()));
        InMemoryMcpCapabilityCatalog catalog = new InMemoryMcpCapabilityCatalog();
        DefaultMcpDiscoveryService service = new DefaultMcpDiscoveryService(registry, catalog,
                new FakeClientFactory(client), Arrays.asList(VERSION), 10);

        assertCode(ErrorCodes.MCP_PROTOCOL_INCOMPATIBLE, new Runnable() {
            public void run() {
                service.refresh(TENANT, SERVER);
            }
        });
        assertSame(McpRegistrationStatus.INCOMPATIBLE, registry.status);
    }

    @Test
    public void refreshMarksReviewRequiredOnIdentityChange() {
        FakeClient client = new FakeClient(new McpInitialization(VERSION, IDENTITY, ALL));
        FakeRegistry registry = new FakeRegistry(registration("other-name", IDENTITY.getVersion()));
        InMemoryMcpCapabilityCatalog catalog = new InMemoryMcpCapabilityCatalog();
        DefaultMcpDiscoveryService service = new DefaultMcpDiscoveryService(registry, catalog,
                new FakeClientFactory(client), Arrays.asList(VERSION), 10);

        assertCode(ErrorCodes.MCP_SERVER_IDENTITY_CHANGED, new Runnable() {
            public void run() {
                service.refresh(TENANT, SERVER);
            }
        });
        assertSame(McpRegistrationStatus.REVIEW_REQUIRED, registry.status);
    }

    private static DefaultMcpDiscoveryService service(McpClientFactory factory, int maxPages) {
        return new DefaultMcpDiscoveryService(emptyRegistry(), new InMemoryMcpCapabilityCatalog(), factory,
                Arrays.asList(VERSION), maxPages, new java.util.function.Supplier<String>() {
                    public String get() { return "snapshot-1"; }
                }, new java.util.function.LongSupplier() {
                    public long getAsLong() { return 42L; }
                });
    }

    private static McpServerRegistry emptyRegistry() {
        return new FakeRegistry(null);
    }

    private static McpServerRegistration registration(String approvedName, String approvedVersion) {
        return new McpServerRegistration(TENANT, SERVER, McpTransportProfile.STDIO, "node echo", null,
                approvedName, approvedVersion, McpRegistrationStatus.PENDING_REVIEW);
    }

    private static List<McpToolDescriptor> tools(McpToolDescriptor tool) {
        List<McpToolDescriptor> values = new ArrayList<McpToolDescriptor>();
        values.add(tool);
        return values;
    }

    private static McpToolDescriptor descriptor(String name) {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        return new McpToolDescriptor(name, "echo tool", schema);
    }

    private static void assertCode(String expectedCode, Runnable action) {
        try {
            action.run();
            fail("expected ClaraException with code " + expectedCode);
        } catch (ClaraException exception) {
            assertEquals(expectedCode, exception.getError().getCode());
        }
    }

    private static final class FakeRegistry implements McpServerRegistry {
        private final McpServerRegistration registration;
        private McpRegistrationStatus status;

        private FakeRegistry(McpServerRegistration registration) { this.registration = registration; }

        public void register(McpServerRegistration value) { }
        public void updateStatus(String tenantId, String serverKey, McpRegistrationStatus value) {
            this.status = value;
        }
        public List<McpServerRegistration> listEnabled(String tenantId) { return Collections.emptyList(); }
        public McpServerRegistration get(String tenantId, String serverKey) {
            if (registration == null) { throw new IllegalStateException("not registered"); }
            return registration;
        }
    }

    private static final class FakeClientFactory implements McpClientFactory {
        private final McpClient client;

        private FakeClientFactory(McpClient client) { this.client = client; }

        public McpClient create(McpServerRegistration registration) { return client; }
    }

    private static final class FakeClient implements McpClient {
        private final McpInitialization initialization;
        private final List<McpPage<McpToolDescriptor>> toolPages = new ArrayList<McpPage<McpToolDescriptor>>();
        private final List<McpResourceDescriptor> resources = new ArrayList<McpResourceDescriptor>();
        private final List<McpResourceTemplateDescriptor> templates =
                new ArrayList<McpResourceTemplateDescriptor>();
        private int toolPageIndex;
        private int closeCount;

        private FakeClient(McpInitialization initialization) { this.initialization = initialization; }

        public McpInitialization initialize(List<String> supportedProtocolVersions) { return initialization; }
        public McpPage<McpToolDescriptor> listTools(String cursor) {
            if (toolPages.isEmpty()) {
                return new McpPage<McpToolDescriptor>(Collections.<McpToolDescriptor>emptyList(), null);
            }
            int index = Math.min(toolPageIndex, toolPages.size() - 1);
            toolPageIndex++;
            return toolPages.get(index);
        }
        public McpPage<McpResourceDescriptor> listResources(String cursor) {
            return new McpPage<McpResourceDescriptor>(resources, null);
        }
        public McpPage<McpResourceTemplateDescriptor> listResourceTemplates(String cursor) {
            return new McpPage<McpResourceTemplateDescriptor>(templates, null);
        }
        public McpPage<McpPromptDescriptor> listPrompts(String cursor) {
            return new McpPage<McpPromptDescriptor>(Collections.<McpPromptDescriptor>emptyList(), null);
        }
        public McpToolResult callTool(McpToolCall call) { throw new UnsupportedOperationException(); }
        public McpResourceContent readResource(McpResourceRequest request) {
            throw new UnsupportedOperationException();
        }
        public McpPromptResult getPrompt(McpPromptRequest request) {
            throw new UnsupportedOperationException();
        }
        public void close() throws IOException { closeCount++; }
    }
}
