package com.clara.tool;

import com.clara.context.CallContext;
import com.clara.context.ConvContext;
import com.clara.core.skill.IToolInvoker;
import com.clara.core.tool.IToolInvocation;
import com.clara.dto.tool.ToolResult;
import com.clara.exception.ClaraException;
import com.clara.exception.ErrorCodes;
import com.clara.mcp.api.IMcpGateway;
import com.clara.mcp.client.call.McpPromptRequest;
import com.clara.mcp.client.call.McpPromptResult;
import com.clara.mcp.client.call.McpResourceContent;
import com.clara.mcp.client.call.McpResourceRequest;
import com.clara.mcp.client.call.McpToolCall;
import com.clara.mcp.client.call.McpToolResult;
import com.clara.mcp.tool.ExternalToolBinding;
import com.clara.mcp.tool.ExternalToolCatalog;
import com.clara.skill.registry.SkillRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link CompositeToolInvoker} 的行为测试：本地技能命中、外部工具命中、未命中、降级分支，均使用假实现，
 * 不依赖真实脚本执行与 MCP 服务端。
 */
public class CompositeToolInvokerTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void localToolHitDelegatesToLocalInvoker() throws Exception {
        RecordingToolInvoker local = new RecordingToolInvoker();
        RecordingMcpGateway gateway = new RecordingMcpGateway();
        CompositeToolInvoker invoker = new CompositeToolInvoker(skillRegistryWithEcho(),
                local, gateway, new ExternalToolCatalog());

        ToolResult result = invoker.invoke(invocation("c1", "echo", "{\"text\":\"hi\"}"));

        assertEquals("c1", result.getCallId());
        assertEquals("{\"local\":true}", result.getOutput());
        assertNotNull(local.received);
        assertEquals("echo", local.received.name());
        assertNull(gateway.received);
    }

    @Test
    public void externalToolHitCallsMcpWithRoutingAndJsonOutput() throws Exception {
        ConvContext conversation = new ConvContext("user", "conv", null, null);
        CallContext call = conversation.newCall(10_000L);
        ConvContext.bind(conversation);
        try {
            ExternalToolCatalog catalog = new ExternalToolCatalog();
            catalog.register("remote.tool",
                    new ExternalToolBinding("tenant-a", "server-a", "snap-1", "hash-1"));
            RecordingMcpGateway gateway = new RecordingMcpGateway();
            gateway.result = mcpResult();
            CompositeToolInvoker invoker = new CompositeToolInvoker(skillRegistryWithEcho(),
                    new RecordingToolInvoker(), gateway, catalog);

            ToolResult result = invoker.invoke(invocation("c9", "remote.tool", "{\"text\":\"hi\"}"));

            assertEquals("c9", result.getCallId());
            assertTrue(result.getOutput().contains("hello"));
            McpToolCall received = gateway.received;
            assertNotNull(received);
            assertEquals("tenant-a", received.getTenantId());
            assertEquals("server-a", received.getServerKey());
            assertEquals("snap-1", received.getSnapshotId());
            assertEquals("remote.tool", received.getToolName());
            assertEquals("hash-1", received.getSchemaHash());
            assertEquals("hi", received.getArguments().get("text"));
            assertSame(conversation, received.getConversation());
            assertSame(call, received.getCall());
        } finally {
            ConvContext.clear();
        }
    }

    @Test
    public void unknownToolThrowsToolNotFound() throws Exception {
        CompositeToolInvoker invoker = new CompositeToolInvoker(skillRegistryWithEcho(),
                new RecordingToolInvoker(), new RecordingMcpGateway(), new ExternalToolCatalog());

        assertCode(ErrorCodes.SKILL_NOT_FOUND, new Runnable() {
            public void run() {
                invoker.invoke(invocation("c1", "missing", "{}"));
            }
        });
    }

    @Test
    public void emptyCatalogAndNonLocalToolThrows() throws Exception {
        ExternalToolCatalog catalog = new ExternalToolCatalog();
        CompositeToolInvoker invoker = new CompositeToolInvoker(skillRegistryWithEcho(),
                new RecordingToolInvoker(), new RecordingMcpGateway(), catalog);

        assertCode(ErrorCodes.SKILL_NOT_FOUND, new Runnable() {
            public void run() {
                invoker.invoke(invocation("c1", "another.remote", "{}"));
            }
        });
    }

    @Test
    public void nullGatewayDegradesExternalBranch() throws Exception {
        ExternalToolCatalog catalog = new ExternalToolCatalog();
        catalog.register("remote.tool",
                new ExternalToolBinding("tenant-a", "server-a", "snap-1", "hash-1"));
        CompositeToolInvoker invoker = new CompositeToolInvoker(skillRegistryWithEcho(),
                new RecordingToolInvoker(), null, catalog);

        assertCode(ErrorCodes.CAPABILITY_NOT_SUPPORTED, new Runnable() {
            public void run() {
                invoker.invoke(invocation("c1", "remote.tool", "{}"));
            }
        });
    }

    private SkillRegistry skillRegistryWithEcho() throws Exception {
        File root = folder.newFolder("skills");
        File tools = new File(root, "tools");
        assertTrue(tools.mkdirs());
        String json = "{\"id\":\"echo\",\"name\":\"echo\",\"type\":\"PYTHON\","
                + "\"description\":\"echo tool\",\"script\":\"print('x')\"}";
        Files.write(new File(tools, "echo.json").toPath(), json.getBytes(StandardCharsets.UTF_8));
        SkillRegistry registry = new SkillRegistry(root, null, null, null, null);
        registry.reload();
        return registry;
    }

    private static IToolInvocation invocation(final String callId, final String name, final String arguments) {
        return new IToolInvocation() {
            public String callId() {
                return callId;
            }

            public String name() {
                return name;
            }

            public String arguments() {
                return arguments;
            }
        };
    }

    private static McpToolResult mcpResult() {
        List<Map<String, Object>> content = new ArrayList<>();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "text");
        item.put("text", "hello");
        content.add(item);
        return new McpToolResult(content, false);
    }

    private static void assertCode(String expectedCode, Runnable action) {
        try {
            action.run();
            fail("expected ClaraException with code " + expectedCode);
        } catch (ClaraException exception) {
            assertEquals(expectedCode, exception.getError().getCode());
        }
    }

    private static final class RecordingToolInvoker implements IToolInvoker {
        private IToolInvocation received;

        public ToolResult invoke(IToolInvocation invocation) {
            this.received = invocation;
            return new ToolResult(invocation.callId(), "{\"local\":true}");
        }
    }

    private static final class RecordingMcpGateway implements IMcpGateway {
        private McpToolCall received;
        private McpToolResult result;

        public McpToolResult callTool(McpToolCall call) {
            this.received = call;
            return result;
        }

        public McpResourceContent readResource(McpResourceRequest request) {
            throw new UnsupportedOperationException();
        }

        public McpPromptResult getPrompt(McpPromptRequest request) {
            throw new UnsupportedOperationException();
        }
    }
}
