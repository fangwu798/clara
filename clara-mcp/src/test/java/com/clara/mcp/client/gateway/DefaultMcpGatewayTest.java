package com.clara.mcp.client.gateway;

import com.clara.context.CallContext;
import com.clara.context.CancellationToken;
import com.clara.context.ConvContext;
import com.clara.exception.ClaraError;
import com.clara.exception.ClaraException;
import com.clara.enums.ErrorCategoryEnum;
import com.clara.exception.ErrorCodes;
import com.clara.mcp.api.IMcpCapabilityCatalog;
import com.clara.mcp.api.IMcpClient;
import com.clara.mcp.api.IMcpClientFactory;
import com.clara.mcp.api.IMcpServerRegistry;
import com.clara.mcp.client.McpInitialization;
import com.clara.mcp.client.McpPage;
import com.clara.mcp.client.McpServerCapabilities;
import com.clara.mcp.client.McpServerIdentity;
import com.clara.mcp.client.call.McpPromptRequest;
import com.clara.mcp.client.call.McpPromptResult;
import com.clara.mcp.client.call.McpResourceContent;
import com.clara.mcp.client.call.McpResourceRequest;
import com.clara.mcp.client.call.McpToolCall;
import com.clara.mcp.client.call.McpToolResult;
import com.clara.mcp.registry.InMemoryMcpCapabilityCatalog;
import com.clara.mcp.registry.McpHealthStatus;
import com.clara.mcp.registry.McpRegistrationStatus;
import com.clara.mcp.registry.McpServerRegistration;
import com.clara.mcp.registry.McpServerSnapshot;
import com.clara.mcp.registry.McpTransportProfile;
import com.clara.mcp.registry.capability.McpPromptDescriptor;
import com.clara.mcp.registry.capability.McpResourceDescriptor;
import com.clara.mcp.registry.capability.McpResourceTemplateDescriptor;
import com.clara.mcp.registry.capability.McpToolDescriptor;
import java.io.IOException;
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
 * {@link DefaultMcpGateway} 的行为测试：全部依赖用假实现，不依赖真实 MCP 服务端与网络。
 */
public class DefaultMcpGatewayTest {
    private static final String TENANT = "tenant-1";
    private static final String SERVER = "echo-server";
    private static final String SNAPSHOT = "snapshot-1";
    private static final String VERSION = "2024-11-05";
    private static final McpServerIdentity IDENTITY = new McpServerIdentity("echo", "1.0.0");
    private static final McpServerCapabilities CAPS =
            new McpServerCapabilities(true, true, true, false, false, false);

    @Test
    public void callToolInvokesClientAndClosesIt() {
        FakeClient client = new FakeClient(initialization());
        client.toolResult = new McpToolResult(content(), false);
        FakeClientFactory factory = new FakeClientFactory(client);
        DefaultMcpGateway gateway = new DefaultMcpGateway(registry(ACTIVE_REGISTRATION),
                stubCatalog(snapshot(tools(descriptor("echo")))), factory);
        McpToolCall call = toolCall("echo", descriptor("echo").getSchemaHash());

        McpToolResult result = gateway.callTool(call);

        assertEquals(false, result.isError());
        assertEquals(1, result.getContent().size());
        assertEquals(1, client.closeCount);
        assertEquals(1, factory.created);
    }

    @Test
    public void callToolThrowsWhenCapabilityNotFound() {
        FakeClient client = new FakeClient(initialization());
        FakeClientFactory factory = new FakeClientFactory(client);
        StubCatalog catalog = stubCatalog(snapshot(Collections.<McpToolDescriptor>emptyList()));
        catalog.toolFailure = error(ErrorCodes.MCP_CAPABILITY_NOT_FOUND);
        DefaultMcpGateway gateway = new DefaultMcpGateway(registry(ACTIVE_REGISTRATION), catalog, factory);

        assertCode(ErrorCodes.MCP_CAPABILITY_NOT_FOUND, new Runnable() {
            public void run() {
                gateway.callTool(toolCall("missing", "any-hash"));
            }
        });
        assertEquals(0, factory.created);
    }

    @Test
    public void callToolThrowsWhenSchemaMismatch() {
        McpToolDescriptor tool = descriptor("echo");
        McpServerSnapshot snapshot = snapshot(tools(tool));
        InMemoryMcpCapabilityCatalog catalog = new InMemoryMcpCapabilityCatalog();
        catalog.publish(snapshot);
        FakeClient client = new FakeClient(initialization());
        FakeClientFactory factory = new FakeClientFactory(client);
        DefaultMcpGateway gateway = new DefaultMcpGateway(registry(ACTIVE_REGISTRATION), catalog, factory);

        assertCode(ErrorCodes.MCP_SCHEMA_MISMATCH, new Runnable() {
            public void run() {
                gateway.callTool(toolCall("echo", "deadbeef"));
            }
        });
        assertEquals(0, factory.created);
    }

    @Test
    public void callToolThrowsWhenServerNotFound() {
        FakeClientFactory factory = new FakeClientFactory(new FakeClient(initialization()));
        DefaultMcpGateway gateway = new DefaultMcpGateway(registry(null),
                stubCatalog(snapshot(tools(descriptor("echo")))), factory);

        assertCode(ErrorCodes.MCP_SERVER_NOT_FOUND, new Runnable() {
            public void run() {
                gateway.callTool(toolCall("echo", "hash"));
            }
        });
        assertEquals(0, factory.created);
    }

    @Test
    public void callToolThrowsWhenServerNotActive() {
        McpServerRegistration disabled = new McpServerRegistration(TENANT, SERVER,
                McpTransportProfile.STDIO, "node echo", null, IDENTITY.getName(),
                IDENTITY.getVersion(), McpRegistrationStatus.REVIEW_REQUIRED);
        FakeClientFactory factory = new FakeClientFactory(new FakeClient(initialization()));
        DefaultMcpGateway gateway = new DefaultMcpGateway(registry(disabled),
                stubCatalog(snapshot(tools(descriptor("echo")))), factory);

        assertCode(ErrorCodes.MCP_SERVER_NOT_ACTIVE, new Runnable() {
            public void run() {
                gateway.callTool(toolCall("echo", "hash"));
            }
        });
        assertEquals(0, factory.created);
    }

    @Test
    public void callToolThrowsWhenIdentityDriftsAndStillClosesClient() {
        FakeClient client = new FakeClient(new McpInitialization(VERSION,
                new McpServerIdentity("other-server", "9.9.9"), CAPS));
        FakeClientFactory factory = new FakeClientFactory(client);
        DefaultMcpGateway gateway = new DefaultMcpGateway(registry(ACTIVE_REGISTRATION),
                stubCatalog(snapshot(tools(descriptor("echo")))), factory);

        assertCode(ErrorCodes.MCP_CAPABILITY_CHANGED, new Runnable() {
            public void run() {
                gateway.callTool(toolCall("echo", descriptor("echo").getSchemaHash()));
            }
        });
        assertEquals(1, client.closeCount);
    }

    @Test
    public void callToolClosesClientWhenOperationFails() {
        FakeClient client = new FakeClient(initialization());
        client.callFailure = error(ErrorCodes.MCP_DISCOVERY_FAILED);
        FakeClientFactory factory = new FakeClientFactory(client);
        DefaultMcpGateway gateway = new DefaultMcpGateway(registry(ACTIVE_REGISTRATION),
                stubCatalog(snapshot(tools(descriptor("echo")))), factory);

        assertCode(ErrorCodes.MCP_DISCOVERY_FAILED, new Runnable() {
            public void run() {
                gateway.callTool(toolCall("echo", descriptor("echo").getSchemaHash()));
            }
        });
        assertEquals(1, client.closeCount);
    }

    @Test
    public void callToolRejectsNull() {
        DefaultMcpGateway gateway = new DefaultMcpGateway(registry(ACTIVE_REGISTRATION),
                stubCatalog(snapshot(tools(descriptor("echo")))), new FakeClientFactory(null));
        try {
            gateway.callTool(null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("call"));
        }
    }

    private static final McpServerRegistration ACTIVE_REGISTRATION = new McpServerRegistration(TENANT,
            SERVER, McpTransportProfile.STDIO, "node echo", null, IDENTITY.getName(),
            IDENTITY.getVersion(), McpRegistrationStatus.ACTIVE);

    private static McpInitialization initialization() {
        return new McpInitialization(VERSION, IDENTITY, CAPS);
    }

    private static IMcpServerRegistry registry(final McpServerRegistration value) {
        return new IMcpServerRegistry() {
            public void register(McpServerRegistration registration) { }
            public void updateStatus(String tenantId, String serverKey, McpRegistrationStatus status) { }
            public List<McpServerRegistration> listEnabled(String tenantId) {
                return Collections.emptyList();
            }
            public McpServerRegistration get(String tenantId, String serverKey) { return value; }
        };
    }

    private static StubCatalog stubCatalog(McpServerSnapshot snapshot) {
        return new StubCatalog(snapshot);
    }

    private static McpServerSnapshot snapshot(List<McpToolDescriptor> tools) {
        return new McpServerSnapshot(SNAPSHOT, TENANT, SERVER, 1L, VERSION, IDENTITY, CAPS, tools,
                Collections.<McpResourceDescriptor>emptyList(),
                Collections.<McpResourceTemplateDescriptor>emptyList(),
                Collections.<McpPromptDescriptor>emptyList(), McpHealthStatus.HEALTHY);
    }

    private static List<McpToolDescriptor> tools(McpToolDescriptor tool) {
        List<McpToolDescriptor> values = new ArrayList<>();
        values.add(tool);
        return values;
    }

    private static McpToolDescriptor descriptor(String name) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        return new McpToolDescriptor(name, "echo tool", schema);
    }

    private static McpToolCall toolCall(String toolName, String schemaHash) {
        ConvContext conversation = new ConvContext("user-1", "conv-1", null, null);
        CallContext call = new CallContext("trace-1", System.currentTimeMillis() + 10000L,
                new CancellationToken());
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("text", "hi");
        return new McpToolCall(TENANT, SERVER, SNAPSHOT, toolName, schemaHash, arguments, conversation, call);
    }

    private static List<Map<String, Object>> content() {
        List<Map<String, Object>> content = new ArrayList<>();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "text");
        item.put("text", "hello");
        content.add(item);
        return content;
    }

    private static ClaraException error(String code) {
        return new ClaraException(ClaraError.builder(code, ErrorCategoryEnum.EXTERNAL_SERVICE).build());
    }

    private static void assertCode(String expectedCode, Runnable action) {
        try {
            action.run();
            fail("expected ClaraException with code " + expectedCode);
        } catch (ClaraException exception) {
            assertEquals(expectedCode, exception.getError().getCode());
        }
    }

    private static final class StubCatalog implements IMcpCapabilityCatalog {
        private final McpServerSnapshot snapshot;
        private ClaraException toolFailure;

        private StubCatalog(McpServerSnapshot snapshot) { this.snapshot = snapshot; }

        public void publish(McpServerSnapshot value) { }
        public McpServerSnapshot latest(String tenantId, String serverKey) { return snapshot; }
        public McpServerSnapshot get(String tenantId, String serverKey, String snapshotId) { return snapshot; }
        public McpToolDescriptor resolveTool(String tenantId, String serverKey, String snapshotId,
                String toolName, String expectedSchemaHash) {
            if (toolFailure != null) { throw toolFailure; }
            return descriptor(toolName);
        }
        public McpResourceDescriptor resolveResource(String tenantId, String serverKey, String snapshotId,
                String uri, String expectedSchemaHash) {
            return null;
        }
        public McpPromptDescriptor resolvePrompt(String tenantId, String serverKey, String snapshotId,
                String promptName, String expectedSchemaHash) {
            return null;
        }
    }

    private static final class FakeClientFactory implements IMcpClientFactory {
        private final IMcpClient client;
        private int created;

        private FakeClientFactory(IMcpClient client) { this.client = client; }

        public IMcpClient create(McpServerRegistration registration) {
            created++;
            return client;
        }
    }

    private static final class FakeClient implements IMcpClient {
        private final McpInitialization initialization;
        private McpToolResult toolResult;
        private RuntimeException callFailure;
        private int closeCount;

        private FakeClient(McpInitialization initialization) { this.initialization = initialization; }

        public McpInitialization initialize(List<String> supportedProtocolVersions) { return initialization; }
        public McpPage<McpToolDescriptor> listTools(String cursor) { throw new UnsupportedOperationException(); }
        public McpPage<McpResourceDescriptor> listResources(String cursor) {
            throw new UnsupportedOperationException();
        }
        public McpPage<McpResourceTemplateDescriptor> listResourceTemplates(String cursor) {
            throw new UnsupportedOperationException();
        }
        public McpPage<McpPromptDescriptor> listPrompts(String cursor) {
            throw new UnsupportedOperationException();
        }
        public McpToolResult callTool(McpToolCall call) {
            if (callFailure != null) { throw callFailure; }
            return toolResult;
        }
        public McpResourceContent readResource(McpResourceRequest request) {
            throw new UnsupportedOperationException();
        }
        public McpPromptResult getPrompt(McpPromptRequest request) {
            throw new UnsupportedOperationException();
        }
        public void close() throws IOException { closeCount++; }
    }
}
