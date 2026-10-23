package com.clara.tool;

import com.clara.context.ConvContext;
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
import com.clara.usage.CapabilityUsageLedger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Defensive branches of tool dispatch: null calls, missing contexts, and graph usage accounting. */
public class CompositeToolInvokerEdgeTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void rejectsNullAndBlankInvocation() throws Exception {
        CompositeToolInvoker invoker = new CompositeToolInvoker(registry(),
                invocation -> new ToolResult(invocation.callId(), "ok"), null,
                new ExternalToolCatalog());

        assertThrows(IllegalArgumentException.class, () -> invoker.invoke(null));
        assertCode(ErrorCodes.INVALID_ARGUMENT, () -> invoker.invoke(invocation("c", "  ", "{}")));
    }

    @Test
    public void recordsCodeGraphUsageForLocalTool() throws Exception {
        CapabilityUsageLedger ledger = new CapabilityUsageLedger();
        CompositeToolInvoker invoker = new CompositeToolInvoker(registry(),
                invocation -> new ToolResult(invocation.callId(), "graph-result"), null,
                new ExternalToolCatalog(), ledger);

        ToolResult result = invoker.invoke(invocation("c1", "code_graph", "{}"));

        assertEquals("graph-result", result.getOutput());
        assertTrue(ledger.report().contains(
                "\u56fe\u8c31\uff1a1 \u6b21\u67e5\u8be2\uff0c\u8fd4\u56de 12 \u5b57\u7b26"));
    }

    @Test
    public void externalCallRequiresConversationContext() throws Exception {
        ExternalToolCatalog catalog = new ExternalToolCatalog();
        catalog.register("remote", new ExternalToolBinding("server", "snap", "hash"));
        CompositeToolInvoker invoker = new CompositeToolInvoker(registry(),
                invocation -> new ToolResult(invocation.callId(), "local"), gateway(), catalog);

        ConvContext.clear();
        assertCode(ErrorCodes.INVALID_ARGUMENT, () -> invoker.invoke(invocation("c", "remote", "{}")));
    }

    @Test
    public void externalCallRequiresCurrentCallAndValidJson() throws Exception {
        ExternalToolCatalog catalog = new ExternalToolCatalog();
        catalog.register("remote", new ExternalToolBinding("server", "snap", "hash"));
        CompositeToolInvoker invoker = new CompositeToolInvoker(registry(),
                invocation -> new ToolResult(invocation.callId(), "local"), gateway(), catalog);

        ConvContext conversation = new ConvContext("user", "conv", null, null);
        ConvContext.bind(conversation);
        try {
            assertCode(ErrorCodes.INVALID_ARGUMENT, () -> invoker.invoke(invocation("c", "remote", "{}")));
        } finally {
            ConvContext.clear();
        }
    }

    private SkillRegistry registry() throws Exception {
        File root = folder.newFolder("skills-" + System.nanoTime());
        File tools = new File(root, "tools");
        assertTrue(tools.mkdirs());
        Files.write(new File(tools, "code_graph.json").toPath(),
                ("{\"id\":\"code_graph\",\"name\":\"code_graph\",\"type\":\"PYTHON\","
                        + "\"description\":\"graph\",\"script\":\"print('x')\"}")
                        .getBytes(StandardCharsets.UTF_8));
        SkillRegistry registry = new SkillRegistry(root, null, null, null, null);
        registry.reload();
        return registry;
    }

    private static IMcpGateway gateway() {
        return new IMcpGateway() {
            @Override
            public McpToolResult callTool(McpToolCall call) {
                return new McpToolResult(Collections.emptyList(), false);
            }

            @Override
            public McpResourceContent readResource(McpResourceRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public McpPromptResult getPrompt(McpPromptRequest request) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static IToolInvocation invocation(String callId, String name, String arguments) {
        return new IToolInvocation() {
            @Override
            public String callId() {
                return callId;
            }

            @Override
            public String name() {
                return name;
            }

            @Override
            public String arguments() {
                return arguments;
            }
        };
    }

    private static void assertCode(String expected, Runnable action) {
        ClaraException error = assertThrows(ClaraException.class, action::run);
        assertEquals(expected, error.getError().getCode());
    }
}
