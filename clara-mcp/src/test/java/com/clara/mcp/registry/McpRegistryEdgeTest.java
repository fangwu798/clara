package com.clara.mcp.registry;

import com.clara.exception.ClaraException;
import com.clara.exception.ErrorCodes;
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
import com.clara.mcp.registry.capability.McpPromptDescriptor;
import com.clara.mcp.registry.capability.McpResourceDescriptor;
import com.clara.mcp.registry.capability.McpResourceTemplateDescriptor;
import com.clara.mcp.registry.capability.McpToolDescriptor;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link DefaultMcpDiscoveryService} 与 {@link McpServerRegistration} 的失败路径。
 *
 * <p>为什么要测：发现失败时「关闭客户端」和「保留原始异常」两条路径很容易写反；
 * 注册项的四个必填校验则是配置加载的第一道闸门，缺一个就会出现难以定位的空指针。
 */
public class McpRegistryEdgeTest {

    private static final String SERVER = "edge-server";
    private static final String VERSION = "2024-11-05";
    private static final McpServerIdentity IDENTITY = new McpServerIdentity("edge", "1.0.0");
    private static final McpServerCapabilities NONE =
            new McpServerCapabilities(false, false, false, false, false, false);

    @Test
    public void discoveryConstructorRejectsInvalidDependencies() {
        InMemoryMcpCapabilityCatalog catalog = new InMemoryMcpCapabilityCatalog();
        FakeRegistry registry = new FakeRegistry(null);
        FakeFactory factory = new FakeFactory(new FakeClient(new McpInitialization(VERSION, IDENTITY, NONE)));
        try {
            new DefaultMcpDiscoveryService(null, catalog, factory, Collections.singletonList(VERSION), 1);
            fail();
        } catch (IllegalArgumentException expected) {
            assertEquals("dependencies must not be null", expected.getMessage());
        }
        try {
            new DefaultMcpDiscoveryService(registry, catalog, factory, Collections.<String>emptyList(), 1);
            fail();
        } catch (IllegalArgumentException expected) {
            assertEquals("supportedVersions must not be empty", expected.getMessage());
        }
        try {
            new DefaultMcpDiscoveryService(registry, catalog, factory, Collections.singletonList(VERSION), 0);
            fail();
        } catch (IllegalArgumentException expected) {
            assertEquals("maxPages must be positive", expected.getMessage());
        }
    }

    @Test
    public void discoverRejectsNullRegistration() {
        DefaultMcpDiscoveryService service = service(new FakeClient(new McpInitialization(VERSION, IDENTITY, NONE)));
        try {
            service.discover(null);
            fail();
        } catch (IllegalArgumentException expected) {
            assertEquals("registration must not be null", expected.getMessage());
        }
    }

    @Test
    public void serverWithoutOptionalCapabilitiesStillProducesSnapshot() {
        DefaultMcpDiscoveryService service = service(new FakeClient(new McpInitialization(VERSION, IDENTITY, NONE)));
        McpServerSnapshot snapshot = service.discover(registration(null, null));
        assertTrue(snapshot.getTools().isEmpty());
        assertTrue(snapshot.getResources().isEmpty());
        assertTrue(snapshot.getResourceTemplates().isEmpty());
        assertTrue(snapshot.getPrompts().isEmpty());
    }

    @Test
    public void closeFailureAfterDiscoveryBecomesDiscoveryError() {
        FakeClient client = new FakeClient(new McpInitialization(VERSION, IDENTITY, NONE));
        client.failOnClose = true;
        DefaultMcpDiscoveryService service = service(client);
        try {
            service.discover(registration(null, null));
            fail("close failure must surface");
        } catch (ClaraException expected) {
            assertEquals(ErrorCodes.MCP_DISCOVERY_FAILED, expected.getError().getCode());
        }
    }

    @Test
    public void closeFailureDuringCleanupDoesNotMaskOriginalFailure() {
        FakeClient client = new FakeClient(null);
        client.failOnClose = true;
        DefaultMcpDiscoveryService service = service(client);
        try {
            service.discover(registration(null, null));
            fail("initialize failure must surface");
        } catch (ClaraException expected) {
            assertEquals(ErrorCodes.MCP_DISCOVERY_FAILED, expected.getError().getCode());
        }
    }

    @Test
    public void refreshUnknownServerReportsNotFound() {
        DefaultMcpDiscoveryService service = service(new FakeClient(new McpInitialization(VERSION, IDENTITY, NONE)));
        try {
            service.refresh("missing");
            fail();
        } catch (ClaraException expected) {
            assertEquals(ErrorCodes.MCP_SERVER_NOT_FOUND, expected.getError().getCode());
        }
    }

    @Test
    public void approvedVersionMismatchRequiresReview() {
        DefaultMcpDiscoveryService service = service(new FakeClient(new McpInitialization(VERSION, IDENTITY, NONE)));
        try {
            service.discover(registration(IDENTITY.getName(), "0.9.0"));
            fail();
        } catch (ClaraException expected) {
            assertEquals(ErrorCodes.MCP_SERVER_IDENTITY_CHANGED, expected.getError().getCode());
        }
    }

    @Test
    public void nullPageIsReportedAsDiscoveryFailure() {
        FakeClient client = new FakeClient(new McpInitialization(VERSION, IDENTITY,
                new McpServerCapabilities(true, false, false, false, false, false)));
        client.nullToolsPage = true;
        DefaultMcpDiscoveryService service = service(client);
        try {
            service.discover(registration(null, null));
            fail();
        } catch (ClaraException expected) {
            assertEquals(ErrorCodes.MCP_DISCOVERY_FAILED, expected.getError().getCode());
        }
    }

    @Test
    public void registrationValidatesRequiredFields() {
        expectRegistrationFailure(null, McpTransportProfile.STDIO, "addr", McpRegistrationStatus.ACTIVE,
                "serverKey is invalid");
        expectRegistrationFailure("bad key", McpTransportProfile.STDIO, "addr", McpRegistrationStatus.ACTIVE,
                "serverKey is invalid");
        expectRegistrationFailure(SERVER, null, "addr", McpRegistrationStatus.ACTIVE,
                "transportProfile must not be null");
        expectRegistrationFailure(SERVER, McpTransportProfile.STDIO, "  ", McpRegistrationStatus.ACTIVE,
                "address must not be blank");
        expectRegistrationFailure(SERVER, McpTransportProfile.STDIO, "addr", null,
                "status must not be null");
    }

    private static void expectRegistrationFailure(String key, McpTransportProfile profile, String address,
            McpRegistrationStatus status, String expected) {
        try {
            new McpServerRegistration(key, profile, address, null, null, null, status);
            fail("expected failure: " + expected);
        } catch (IllegalArgumentException failure) {
            assertEquals(expected, failure.getMessage());
        }
    }

    private static DefaultMcpDiscoveryService service(FakeClient client) {
        return new DefaultMcpDiscoveryService(new FakeRegistry(null), new InMemoryMcpCapabilityCatalog(),
                new FakeFactory(client), Collections.singletonList(VERSION), 5);
    }

    private static McpServerRegistration registration(String approvedName, String approvedVersion) {
        return new McpServerRegistration(SERVER, McpTransportProfile.STDIO, "node echo", null,
                approvedName, approvedVersion, McpRegistrationStatus.ACTIVE);
    }

    private static final class FakeRegistry implements IMcpServerRegistry {
        private final McpServerRegistration registration;

        private FakeRegistry(McpServerRegistration registration) {
            this.registration = registration;
        }

        public void register(McpServerRegistration value) { }

        public void updateStatus(String serverKey, McpRegistrationStatus value) { }

        public List<McpServerRegistration> listEnabled() {
            return Collections.emptyList();
        }

        public McpServerRegistration get(String serverKey) {
            return registration;
        }
    }

    private static final class FakeFactory implements IMcpClientFactory {
        private final IMcpClient client;

        private FakeFactory(IMcpClient client) {
            this.client = client;
        }

        public IMcpClient create(McpServerRegistration registration) {
            return client;
        }
    }

    private static final class FakeClient implements IMcpClient {
        private final McpInitialization initialization;
        private boolean failOnClose;
        private boolean nullToolsPage;

        private FakeClient(McpInitialization initialization) {
            this.initialization = initialization;
        }

        public McpInitialization initialize(List<String> supportedProtocolVersions) {
            if (initialization == null) {
                throw com.clara.mcp.McpErrors.error(ErrorCodes.MCP_DISCOVERY_FAILED,
                        com.clara.enums.ErrorCategoryEnum.EXTERNAL_SERVICE);
            }
            return initialization;
        }

        public McpPage<McpToolDescriptor> listTools(String cursor) {
            if (nullToolsPage) {
                return null;
            }
            return new McpPage<>(Collections.<McpToolDescriptor>emptyList(), null);
        }

        public McpPage<McpResourceDescriptor> listResources(String cursor) {
            return new McpPage<>(Collections.<McpResourceDescriptor>emptyList(), null);
        }

        public McpPage<McpResourceTemplateDescriptor> listResourceTemplates(String cursor) {
            return new McpPage<>(Collections.<McpResourceTemplateDescriptor>emptyList(), null);
        }

        public McpPage<McpPromptDescriptor> listPrompts(String cursor) {
            return new McpPage<>(Collections.<McpPromptDescriptor>emptyList(), null);
        }

        public McpToolResult callTool(McpToolCall call) {
            throw new UnsupportedOperationException();
        }

        public McpResourceContent readResource(McpResourceRequest request) {
            throw new UnsupportedOperationException();
        }

        public McpPromptResult getPrompt(McpPromptRequest request) {
            throw new UnsupportedOperationException();
        }

        public void close() throws IOException {
            if (failOnClose) {
                throw new IOException("close failed");
            }
        }
    }
}