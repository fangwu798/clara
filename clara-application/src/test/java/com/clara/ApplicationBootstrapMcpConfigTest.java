package com.clara;

import com.clara.mcp.client.McpClient;
import com.clara.mcp.client.McpClientFactory;
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
import com.clara.mcp.registry.McpServerRegistration;
import com.clara.mcp.registry.McpServerSnapshot;
import com.clara.mcp.registry.capability.McpPromptDescriptor;
import com.clara.mcp.registry.capability.McpResourceDescriptor;
import com.clara.mcp.registry.capability.McpResourceTemplateDescriptor;
import com.clara.mcp.registry.capability.McpToolDescriptor;
import com.clara.model.embedding.HashingEmbeddingClient;
import com.clara.tool.ExternalToolBinding;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link ApplicationBootstrap#loadMcpServers(String, java.nio.file.Path)} 的接入链路测试：用假工厂 / 假客户端
 * 返回固定的 initialize 与 tools/list 结果，验证「配置 → 注册 → 发现 → 登记」与单服务端失败隔离，不连网络。
 */
public class ApplicationBootstrapMcpConfigTest {

    private static final int DIMENSION = 1024;
    private static final String TENANT = "tenant-1";
    private static final String VERSION = "2024-11-05";

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void loadMcpServersRegistersDiscoversAndRegistersTools() throws IOException {
        write(folder.getRoot(), "alpha.json", stdioJson("alpha", "alpha.js"));
        FakeClientFactory factory = new FakeClientFactory();
        factory.put("alpha", new FakeClient("alpha", tool("echo")));
        ApplicationBootstrap bootstrap = bootstrap(factory);
        try {
            int discovered = bootstrap.loadMcpServers(TENANT, folder.getRoot().toPath());

            assertEquals(1, discovered);
            assertNotNull(bootstrap.mcpServerRegistry().get(TENANT, "alpha"));
            McpServerSnapshot snapshot = bootstrap.mcpCapabilityCatalog().latest(TENANT, "alpha");
            assertNotNull(snapshot);
            assertEquals(1, snapshot.getTools().size());

            ExternalToolBinding binding = bootstrap.externalToolCatalog().find("echo");
            assertNotNull(binding);
            assertEquals(TENANT, binding.getTenantId());
            assertEquals("alpha", binding.getServerKey());
            assertEquals(snapshot.getSnapshotId(), binding.getSnapshotId());
            assertEquals(snapshot.getTools().get(0).getSchemaHash(), binding.getSchemaHash());
        } finally {
            bootstrap.close();
        }
    }

    @Test
    public void singleServerDiscoveryFailureDoesNotBreakOthers() throws IOException {
        write(folder.getRoot(), "servers.json", twoServersJson("alpha", "alpha.js", "beta", "beta.js"));
        FakeClientFactory factory = new FakeClientFactory();
        factory.put("alpha", new FakeClient("alpha", tool("echo")));
        factory.put("beta", new FailingClient());
        ApplicationBootstrap bootstrap = bootstrap(factory);
        try {
            int discovered = bootstrap.loadMcpServers(TENANT, folder.getRoot().toPath());

            assertEquals(1, discovered);
            assertNotNull(bootstrap.mcpCapabilityCatalog().latest(TENANT, "alpha"));
            assertNotNull(bootstrap.externalToolCatalog().find("echo"));
            // 失败的服务端已注册但未发布快照，也不影响其余服务端
            assertNotNull(bootstrap.mcpServerRegistry().get(TENANT, "beta"));
            assertNull(bootstrap.mcpCapabilityCatalog().latest(TENANT, "beta"));
        } finally {
            bootstrap.close();
        }
    }

    @Test
    public void missingConfigDirectoryYieldsZero() {
        FakeClientFactory factory = new FakeClientFactory();
        ApplicationBootstrap bootstrap = bootstrap(factory);
        try {
            int discovered = bootstrap.loadMcpServers(TENANT,
                    folder.getRoot().toPath().resolve("does-not-exist"));

            assertEquals(0, discovered);
            assertTrue(bootstrap.externalToolCatalog().snapshot().isEmpty());
        } finally {
            bootstrap.close();
        }
    }

    private static ApplicationBootstrap bootstrap(McpClientFactory factory) {
        return new ApplicationBootstrap(new HashingEmbeddingClient(DIMENSION), DIMENSION, factory);
    }

    private static void write(File directory, String name, String content) throws IOException {
        Files.write(directory.toPath().resolve(name), content.getBytes(StandardCharsets.UTF_8));
    }

    private static String stdioJson(String serverKey, String arg) {
        return "{ \"mcpServers\": { \"" + serverKey + "\": { \"command\": \"node\", \"args\": [\""
                + arg + "\"] } } }";
    }

    private static String twoServersJson(String keyA, String argA, String keyB, String argB) {
        return "{ \"mcpServers\": { \"" + keyA + "\": { \"command\": \"node\", \"args\": [\"" + argA
                + "\"] }, \"" + keyB + "\": { \"command\": \"node\", \"args\": [\"" + argB + "\"] } } }";
    }

    private static McpToolDescriptor tool(String name) {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        return new McpToolDescriptor(name, name + " tool", schema);
    }

    private static final class FakeClientFactory implements McpClientFactory {
        private final Map<String, McpClient> clients = new HashMap<String, McpClient>();

        private FakeClientFactory put(String serverKey, McpClient client) {
            clients.put(serverKey, client);
            return this;
        }

        public McpClient create(McpServerRegistration registration) {
            McpClient client = clients.get(registration.getServerKey());
            if (client == null) {
                throw new IllegalStateException("no fake client for " + registration.getServerKey());
            }
            return client;
        }
    }

    private static final class FakeClient implements McpClient {
        private final McpInitialization initialization;
        private final List<McpToolDescriptor> tools;

        private FakeClient(String serverKey, McpToolDescriptor... toolList) {
            this.initialization = new McpInitialization(VERSION,
                    new McpServerIdentity(serverKey, "1.0.0"),
                    new McpServerCapabilities(true, false, false, false, false, false));
            this.tools = new ArrayList<McpToolDescriptor>();
            Collections.addAll(this.tools, toolList);
        }

        public McpInitialization initialize(List<String> supportedProtocolVersions) { return initialization; }
        public McpPage<McpToolDescriptor> listTools(String cursor) {
            return new McpPage<McpToolDescriptor>(tools, null);
        }
        public McpPage<McpResourceDescriptor> listResources(String cursor) {
            return new McpPage<McpResourceDescriptor>(Collections.<McpResourceDescriptor>emptyList(), null);
        }
        public McpPage<McpResourceTemplateDescriptor> listResourceTemplates(String cursor) {
            return new McpPage<McpResourceTemplateDescriptor>(
                    Collections.<McpResourceTemplateDescriptor>emptyList(), null);
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
        public void close() { }
    }

    private static final class FailingClient implements McpClient {
        public McpInitialization initialize(List<String> supportedProtocolVersions) {
            throw new IllegalStateException("simulated discovery failure");
        }
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
        public McpToolResult callTool(McpToolCall call) { throw new UnsupportedOperationException(); }
        public McpResourceContent readResource(McpResourceRequest request) {
            throw new UnsupportedOperationException();
        }
        public McpPromptResult getPrompt(McpPromptRequest request) {
            throw new UnsupportedOperationException();
        }
        public void close() { }
    }
}
