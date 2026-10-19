package com.clara.mcp.client;

import com.clara.context.CallContext;
import com.clara.context.CancellationToken;
import com.clara.context.ConvContext;
import com.clara.exception.ClaraError;
import com.clara.exception.ClaraException;
import com.clara.exception.ErrorCategory;
import com.clara.exception.ErrorCodes;
import com.clara.mcp.api.IMcpTransport;
import com.clara.mcp.api.IMcpTransportListener;
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
import com.clara.mcp.transport.jsonrpc.JsonRpcError;
import com.clara.mcp.transport.jsonrpc.JsonRpcNotification;
import com.clara.mcp.transport.jsonrpc.JsonRpcRequest;
import com.clara.mcp.transport.jsonrpc.JsonRpcResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link DefaultMcpClient} 的行为测试：用假传输返回预置 JSON-RPC 响应，不依赖真实 MCP 服务端与网络。
 */
public class DefaultMcpClientTest {
    private static final String TENANT = "tenant-1";
    private static final String SERVER = "echo-server";
    private static final String SNAPSHOT = "snapshot-1";

    @Test
    public void initializeParsesResultAndSendsInitializedNotification() {
        FakeTransport transport = new FakeTransport();
        transport.response = success(map(
                "protocolVersion", "2024-11-05",
                "serverInfo", map("name", "echo", "version", "1.0.0"),
                "capabilities", map("tools", map("listChanged", true), "prompts", map())));
        DefaultMcpClient client = new DefaultMcpClient(transport, 1000L);

        McpInitialization initialization = client.initialize(Arrays.asList("2024-11-05"));

        assertEquals("2024-11-05", initialization.getProtocolVersion());
        assertEquals("echo", initialization.getIdentity().getName());
        assertEquals("1.0.0", initialization.getIdentity().getVersion());
        assertTrue(initialization.getCapabilities().supportsTools());
        assertFalse(initialization.getCapabilities().supportsResources());
        assertTrue(initialization.getCapabilities().supportsPrompts());
        assertTrue(initialization.getCapabilities().supportsToolsListChanged());
        assertEquals(1, transport.started);
        assertEquals("initialize", transport.requests.get(0).getMethod());
        assertEquals("2024-11-05", transport.requests.get(0).getParams().get("protocolVersion"));
        assertEquals(1, transport.notifications.size());
        assertEquals("notifications/initialized", transport.notifications.get(0).getMethod());
    }

    @Test
    public void initializeRejectsMissingServerInfo() {
        FakeTransport transport = new FakeTransport();
        transport.response = success(map("protocolVersion", "2024-11-05"));
        final DefaultMcpClient client = new DefaultMcpClient(transport, 1000L);

        assertCode(ErrorCodes.MCP_INVALID_MESSAGE, new Runnable() {
            public void run() { client.initialize(Arrays.asList("2024-11-05")); }
        });
    }

    @Test
    public void listToolsParsesItemsAndCursor() {
        FakeTransport transport = new FakeTransport();
        transport.response = success(map(
                "tools", Arrays.asList(map("name", "echo", "description", "d",
                        "inputSchema", map("type", "object"))),
                "nextCursor", "c2"));
        DefaultMcpClient client = new DefaultMcpClient(transport, 1000L);

        McpPage<McpToolDescriptor> page = client.listTools(null);

        assertEquals(1, page.getItems().size());
        assertEquals("echo", page.getItems().get(0).getName());
        assertEquals("d", page.getItems().get(0).getDescription());
        assertEquals("c2", page.getNextCursor());
        assertEquals("tools/list", transport.requests.get(0).getMethod());
        assertTrue(transport.requests.get(0).getParams().isEmpty());
    }

    @Test
    public void listResourcesParsesItems() {
        FakeTransport transport = new FakeTransport();
        transport.response = success(map("resources", Arrays.asList(
                map("uri", "file://a.txt", "name", "a", "mimeType", "text/plain"))));
        DefaultMcpClient client = new DefaultMcpClient(transport, 1000L);

        McpPage<McpResourceDescriptor> page = client.listResources("cursor-1");

        assertEquals(1, page.getItems().size());
        assertEquals("file://a.txt", page.getItems().get(0).getUri());
        assertEquals("cursor-1", transport.requests.get(0).getParams().get("cursor"));
        assertEquals("resources/list", transport.requests.get(0).getMethod());
    }

    @Test
    public void listResourceTemplatesParsesItems() {
        FakeTransport transport = new FakeTransport();
        transport.response = success(map("resourceTemplates", Arrays.asList(
                map("uriTemplate", "file://{name}", "name", "t", "mimeType", "text/plain"))));
        DefaultMcpClient client = new DefaultMcpClient(transport, 1000L);

        McpPage<McpResourceTemplateDescriptor> page = client.listResourceTemplates(null);

        assertEquals(1, page.getItems().size());
        assertEquals("file://{name}", page.getItems().get(0).getUriTemplate());
        assertEquals("resources/templates/list", transport.requests.get(0).getMethod());
    }

    @Test
    public void listPromptsParsesItemsAndArguments() {
        FakeTransport transport = new FakeTransport();
        transport.response = success(map("prompts", Arrays.asList(
                map("name", "greet", "description", "g",
                        "arguments", Arrays.asList(map("name", "who", "description", "w",
                                "required", true))))));
        DefaultMcpClient client = new DefaultMcpClient(transport, 1000L);

        McpPage<McpPromptDescriptor> page = client.listPrompts(null);

        assertEquals(1, page.getItems().size());
        assertEquals("greet", page.getItems().get(0).getName());
        assertEquals(1, page.getItems().get(0).getArguments().size());
        assertEquals("who", page.getItems().get(0).getArguments().get(0).getName());
        assertTrue(page.getItems().get(0).getArguments().get(0).isRequired());
        assertEquals("prompts/list", transport.requests.get(0).getMethod());
    }

    @Test
    public void callToolParsesContentAndErrorFlag() {
        FakeTransport transport = new FakeTransport();
        transport.response = success(map(
                "content", Arrays.asList(map("type", "text", "text", "hello")),
                "isError", false));
        DefaultMcpClient client = new DefaultMcpClient(transport, 1000L);

        McpToolResult result = client.callTool(toolCall());

        assertFalse(result.isError());
        assertEquals(1, result.getContent().size());
        assertEquals("hello", result.getContent().get(0).get("text"));
        assertEquals("tools/call", transport.requests.get(0).getMethod());
        assertEquals("echo", transport.requests.get(0).getParams().get("name"));
    }

    @Test
    public void callToolParsesErrorFlagTrue() {
        FakeTransport transport = new FakeTransport();
        transport.response = success(map(
                "content", Arrays.asList(map("type", "text", "text", "boom")), "isError", true));
        DefaultMcpClient client = new DefaultMcpClient(transport, 1000L);

        McpToolResult result = client.callTool(toolCall());

        assertTrue(result.isError());
    }

    @Test
    public void readResourceParsesContents() {
        FakeTransport transport = new FakeTransport();
        transport.response = success(map("contents", Arrays.asList(
                map("uri", "file://a.txt", "mimeType", "text/plain", "text", "hi"))));
        DefaultMcpClient client = new DefaultMcpClient(transport, 1000L);

        McpResourceContent content = client.readResource(resourceRequest());

        assertEquals(1, content.getContent().size());
        assertEquals("hi", content.getContent().get(0).get("text"));
        assertEquals("resources/read", transport.requests.get(0).getMethod());
        assertEquals("file://a.txt", transport.requests.get(0).getParams().get("uri"));
    }

    @Test
    public void getPromptParsesDescriptionAndMessages() {
        FakeTransport transport = new FakeTransport();
        transport.response = success(map(
                "description", "greeting",
                "messages", Arrays.asList(map("role", "user",
                        "content", map("type", "text", "text", "hi")))));
        DefaultMcpClient client = new DefaultMcpClient(transport, 1000L);

        McpPromptResult result = client.getPrompt(promptRequest());

        assertEquals("greeting", result.getDescription());
        assertFalse(result.isError());
        assertEquals(1, result.getMessages().size());
        assertEquals("prompts/get", transport.requests.get(0).getMethod());
        assertEquals("greet", transport.requests.get(0).getParams().get("name"));
    }

    @Test
    public void errorResponseThrowsDomainError() {
        FakeTransport transport = new FakeTransport();
        transport.response = new JsonRpcResponse("id-1", null,
                new JsonRpcError(-32601, "method not found", null));
        final DefaultMcpClient client = new DefaultMcpClient(transport, 1000L);

        assertCode(ErrorCodes.MCP_DISCOVERY_FAILED, new Runnable() {
            public void run() { client.listTools(null); }
        });
    }

    @Test
    public void timeoutThrowsRequestTimeout() {
        FakeTransport transport = new FakeTransport();
        transport.never = true;
        final DefaultMcpClient client = new DefaultMcpClient(transport, 30L);

        assertCode(ErrorCodes.MCP_REQUEST_TIMEOUT, new Runnable() {
            public void run() { client.listTools(null); }
        });
        assertEquals(1, transport.cancellations);
    }

    @Test
    public void transportFailurePropagates() {
        FakeTransport transport = new FakeTransport();
        transport.failure = new ClaraException(new ClaraError(ErrorCodes.MCP_TRANSPORT_IO_ERROR,
                ErrorCategory.EXTERNAL_SERVICE, null, true, null, null, null, null, null));
        final DefaultMcpClient client = new DefaultMcpClient(transport, 1000L);

        assertCode(ErrorCodes.MCP_TRANSPORT_IO_ERROR, new Runnable() {
            public void run() { client.listTools(null); }
        });
    }

    @Test
    public void closeClosesTransportOnceAndRejectsFurtherUse() throws IOException {
        FakeTransport transport = new FakeTransport();
        transport.response = success(map("tools", Collections.emptyList()));
        final DefaultMcpClient client = new DefaultMcpClient(transport, 1000L);

        client.close();
        client.close();

        assertEquals(1, transport.closed);
        assertCode(ErrorCodes.MCP_TRANSPORT_CLOSED, new Runnable() {
            public void run() { client.listTools(null); }
        });
    }

    private static McpToolCall toolCall() {
        return new McpToolCall(TENANT, SERVER, SNAPSHOT, "echo", "hash", map("text", "hi"),
                conversation(), call());
    }

    private static McpResourceRequest resourceRequest() {
        return new McpResourceRequest(TENANT, SERVER, SNAPSHOT, "file://a.txt", "hash",
                conversation(), call());
    }

    private static McpPromptRequest promptRequest() {
        return new McpPromptRequest(TENANT, SERVER, SNAPSHOT, "greet", "hash", map("who", "world"),
                conversation(), call());
    }

    private static ConvContext conversation() { return new ConvContext("user-1", "conv-1", null, null); }

    private static CallContext call() {
        return new CallContext("trace-1", System.currentTimeMillis() + 10000L, new CancellationToken());
    }

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            result.put((String) keyValues[index], keyValues[index + 1]);
        }
        return result;
    }

    private static JsonRpcResponse success(Map<String, Object> result) {
        return new JsonRpcResponse("id-1", result, null);
    }

    private static void assertCode(String expectedCode, Runnable action) {
        try {
            action.run();
            fail("expected ClaraException with code " + expectedCode);
        } catch (ClaraException exception) {
            assertEquals(expectedCode, exception.getError().getCode());
        }
    }

    private static final class FakeTransport implements IMcpTransport {
        private final List<JsonRpcRequest> requests = new ArrayList<>();
        private final List<JsonRpcNotification> notifications = new ArrayList<>();
        private JsonRpcResponse response;
        private RuntimeException failure;
        private boolean never;
        private int started;
        private int closed;
        private int cancellations;

        public void start(IMcpTransportListener listener) { started++; }

        public CompletableFuture<JsonRpcResponse> sendRequest(JsonRpcRequest request, long timeoutMillis) {
            requests.add(request);
            CompletableFuture<JsonRpcResponse> future = new CompletableFuture<>();
            if (failure != null) { future.completeExceptionally(failure); return future; }
            if (never) { return future; }
            future.complete(response);
            return future;
        }

        public void sendNotification(JsonRpcNotification notification) { notifications.add(notification); }
        public boolean cancel(String requestId) { cancellations++; return false; }
        public void checkHealth() { }
        public void close() throws IOException { closed++; }
    }
}
