package com.clara.mcp.client;

import com.clara.kernel.CancellationToken;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCodes;
import com.clara.kernel.InvocationContext;
import com.clara.mcp.registry.InMemoryMcpCapabilityCatalog;
import com.clara.mcp.registry.InMemoryMcpServerRegistry;
import com.clara.mcp.registry.McpClientFactory;
import com.clara.mcp.registry.McpHealthStatus;
import com.clara.mcp.registry.McpPromptDescriptor;
import com.clara.mcp.registry.McpRegistrationStatus;
import com.clara.mcp.registry.McpResourceDescriptor;
import com.clara.mcp.registry.McpResourceTemplateDescriptor;
import com.clara.mcp.registry.McpServerRegistration;
import com.clara.mcp.registry.McpServerSnapshot;
import com.clara.mcp.registry.McpToolDescriptor;
import com.clara.mcp.registry.McpTransportProfile;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DefaultMcpGatewayTest {
    @Test
    public void invokesOnlyThePinnedToolSchema() {
        Fixture fixture = new Fixture();
        McpToolResult result = fixture.gateway.callTool(new McpToolCall("server", "snapshot",
                "search", fixture.tool.getSchemaHash(),
                Collections.<String, Object>singletonMap("query", "clara"), context()));
        assertEquals("search", fixture.client.calledTool);
        assertTrue(result.getContent().isEmpty());
        assertTrue(fixture.client.closed);
    }

    @Test
    public void rejectsChangedSchemaBeforeCreatingClient() {
        Fixture fixture = new Fixture();
        try {
            fixture.gateway.callTool(new McpToolCall("server", "snapshot", "search",
                    "different", Collections.<String, Object>emptyMap(), context()));
            fail("expected schema mismatch");
        } catch (ClaraException exception) {
            assertEquals(ErrorCodes.MCP_CAPABILITY_CHANGED, exception.getError().getCode());
            assertEquals(0, fixture.factoryCalls);
        }
    }

    @Test
    public void cancellationStopsCallBeforeClientCreation() {
        Fixture fixture = new Fixture();
        InvocationContext context = context(); context.getCancellationToken().cancel();
        try {
            fixture.gateway.callTool(new McpToolCall("server", "snapshot", "search",
                    fixture.tool.getSchemaHash(), Collections.<String, Object>emptyMap(), context));
            fail("expected cancellation");
        } catch (ClaraException exception) {
            assertEquals(ErrorCodes.INVOCATION_CANCELLED, exception.getError().getCode());
            assertEquals(0, fixture.factoryCalls);
        }
    }

    private static InvocationContext context() {
        return new InvocationContext("tenant", "user", "run", "trace", Long.MAX_VALUE,
                new CancellationToken());
    }

    private static final class Fixture implements McpClientFactory {
        private final McpToolDescriptor tool = new McpToolDescriptor("search", null,
                Collections.<String, Object>singletonMap("type", "object"));
        private final FakeClient client = new FakeClient();
        private int factoryCalls;
        private final DefaultMcpGateway gateway;
        private Fixture() {
            InMemoryMcpServerRegistry registry = new InMemoryMcpServerRegistry();
            registry.register(new McpServerRegistration("tenant", "server", McpTransportProfile.STDIO,
                    "server.exe", null, "test", "1", McpRegistrationStatus.ACTIVE));
            InMemoryMcpCapabilityCatalog catalog = new InMemoryMcpCapabilityCatalog();
            catalog.publish(new McpServerSnapshot("snapshot", "tenant", "server", 1L,
                    "2025-03-26", new McpServerIdentity("test", "1"),
                    new McpServerCapabilities(true, false, false, false, false, false),
                    Arrays.asList(tool), Collections.<McpResourceDescriptor>emptyList(),
                    Collections.<McpResourceTemplateDescriptor>emptyList(),
                    Collections.<McpPromptDescriptor>emptyList(), McpHealthStatus.HEALTHY));
            gateway = new DefaultMcpGateway(registry, catalog, this);
        }
        public McpClient create(McpServerRegistration registration) { factoryCalls++; return client; }
    }

    private static final class FakeClient implements McpClient {
        private String calledTool;
        private boolean closed;
        public McpInitialization initialize(List<String> versions) {
            return new McpInitialization("2025-03-26", new McpServerIdentity("test", "1"),
                    new McpServerCapabilities(true, false, false, false, false, false));
        }
        public McpPage<com.clara.mcp.registry.McpToolDescriptor> listTools(String cursor) {
            throw new UnsupportedOperationException();
        }
        public McpPage<McpResourceDescriptor> listResources(String cursor) { throw new UnsupportedOperationException(); }
        public McpPage<McpResourceTemplateDescriptor> listResourceTemplates(String cursor) {
            throw new UnsupportedOperationException();
        }
        public McpPage<McpPromptDescriptor> listPrompts(String cursor) { throw new UnsupportedOperationException(); }
        public McpToolResult callTool(String name, Map<String, Object> arguments) {
            calledTool = name; return new McpToolResult(Collections.<Map<String, Object>>emptyList(), false);
        }
        public McpResourceContent readResource(String uri) { throw new UnsupportedOperationException(); }
        public McpPromptResult getPrompt(String name, Map<String, Object> arguments) {
            throw new UnsupportedOperationException();
        }
        public void close() throws IOException { closed = true; }
    }
}
