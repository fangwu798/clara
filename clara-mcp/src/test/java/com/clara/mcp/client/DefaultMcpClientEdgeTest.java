package com.clara.mcp.client;

import com.clara.enums.ErrorCategoryEnum;
import com.clara.exception.ClaraException;
import com.clara.exception.ErrorCodes;
import com.clara.mcp.api.IMcpTransport;
import com.clara.mcp.api.IMcpTransportListener;
import com.clara.mcp.client.call.McpPromptRequest;
import com.clara.mcp.client.call.McpResourceRequest;
import com.clara.mcp.client.call.McpToolCall;
import com.clara.mcp.transport.jsonrpc.JsonRpcError;
import com.clara.mcp.transport.jsonrpc.JsonRpcNotification;
import com.clara.mcp.transport.jsonrpc.JsonRpcRequest;
import com.clara.mcp.transport.jsonrpc.JsonRpcResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link DefaultMcpClient} 的参数校验与失败映射。
 *
 * <p>为什么要测：客户端把所有等待异常都收敛成领域错误码。超时如果被当成普通失败，
 * 上层就不会重试；中断如果吞掉了中断标记，取消流程会挂住；服务端返回 error 如果被当成空结果，
 * 调用方会以为「工具没有输出」。这些语义必须逐条钉死。
 */
public class DefaultMcpClientEdgeTest {

    private static final String VERSION = "2024-11-05";

    @Test
    public void constructorRejectsNullTransportAndBadTimeout() {
        try {
            new DefaultMcpClient(null, 1000L);
            fail();
        } catch (IllegalArgumentException expected) {
            assertEquals("transport must not be null", expected.getMessage());
        }
        try {
            new DefaultMcpClient(new FakeTransport(), 0L);
            fail();
        } catch (IllegalArgumentException expected) {
            assertEquals("requestTimeoutMillis must be positive", expected.getMessage());
        }
    }

    @Test
    public void operationsRejectNullArguments() {
        DefaultMcpClient client = new DefaultMcpClient(new FakeTransport(), 1000L);
        try {
            client.initialize(Collections.<String>emptyList());
            fail();
        } catch (IllegalArgumentException expected) {
            assertEquals("supportedProtocolVersions must not be empty", expected.getMessage());
        }
        try {
            client.callTool(null);
            fail();
        } catch (IllegalArgumentException expected) {
            assertEquals("call must not be null", expected.getMessage());
        }
        try {
            client.readResource(null);
            fail();
        } catch (IllegalArgumentException expected) {
            assertEquals("request must not be null", expected.getMessage());
        }
        try {
            client.getPrompt(null);
            fail();
        } catch (IllegalArgumentException expected) {
            assertEquals("request must not be null", expected.getMessage());
        }
    }

    @Test
    public void timeoutCancelsRequestAndMapsToTimeoutError() {
        FakeTransport transport = new FakeTransport();
        transport.answer = Answer.NEVER;
        DefaultMcpClient client = new DefaultMcpClient(transport, 30L);
        try {
            client.initialize(Collections.singletonList(VERSION));
            fail("should time out");
        } catch (ClaraException expected) {
            assertEquals(ErrorCodes.MCP_REQUEST_TIMEOUT, expected.getError().getCode());
            assertTrue("超时必须通知传输层取消在途请求", transport.cancelled);
        }
    }

    @Test
    public void interruptIsPreservedAndMappedToCancellation() {
        FakeTransport transport = new FakeTransport();
        transport.answer = Answer.NEVER;
        DefaultMcpClient client = new DefaultMcpClient(transport, 5000L);
        Thread.currentThread().interrupt();
        try {
            client.initialize(Collections.singletonList(VERSION));
            fail("interrupted wait should abort");
        } catch (ClaraException expected) {
            assertEquals(ErrorCodes.INVOCATION_CANCELLED, expected.getError().getCode());
        } finally {
            assertTrue("中断标记必须保留", Thread.currentThread().isInterrupted());
            Thread.interrupted();
        }
    }

    @Test
    public void executionFailureWithRuntimeCauseIsRethrown() {
        FakeTransport transport = new FakeTransport();
        transport.answer = Answer.FAIL_RUNTIME;
        DefaultMcpClient client = new DefaultMcpClient(transport, 5000L);
        try {
            client.initialize(Collections.singletonList(VERSION));
            fail();
        } catch (IllegalStateException expected) {
            assertEquals("boom", expected.getMessage());
        }
    }

    @Test
    public void executionFailureWithCheckedCauseBecomesTransportIoError() {
        FakeTransport transport = new FakeTransport();
        transport.answer = Answer.FAIL_CHECKED;
        DefaultMcpClient client = new DefaultMcpClient(transport, 5000L);
        try {
            client.initialize(Collections.singletonList(VERSION));
            fail();
        } catch (ClaraException expected) {
            assertEquals(ErrorCodes.MCP_TRANSPORT_IO_ERROR, expected.getError().getCode());
        }
    }

    @Test
    public void nullOrErrorResponseIsRejected() {
        FakeTransport transport = new FakeTransport();
        transport.answer = Answer.NULL;
        DefaultMcpClient client = new DefaultMcpClient(transport, 5000L);
        try {
            client.initialize(Collections.singletonList(VERSION));
            fail();
        } catch (ClaraException expected) {
            assertEquals(ErrorCodes.MCP_INVALID_MESSAGE, expected.getError().getCode());
        }

        transport.answer = Answer.ERROR;
        try {
            client.initialize(Collections.singletonList(VERSION));
            fail();
        } catch (ClaraException expected) {
            assertEquals(ErrorCodes.MCP_DISCOVERY_FAILED, expected.getError().getCode());
        }
    }

    @Test
    public void closeBlocksFurtherCalls() throws Exception {
        FakeTransport transport = new FakeTransport();
        DefaultMcpClient client = new DefaultMcpClient(transport, 1000L);
        client.close();
        client.close();
        try {
            client.initialize(Collections.singletonList(VERSION));
            fail();
        } catch (ClaraException expected) {
            assertEquals(ErrorCodes.MCP_TRANSPORT_CLOSED, expected.getError().getCode());
        }
    }

    @Test
    public void defaultListenerIgnoresTransportEvents() {
        FakeTransport transport = new FakeTransport();
        DefaultMcpClient client = new DefaultMcpClient(transport, 1000L);
        client.initialize(Collections.singletonList(VERSION));
        transport.listener.onNotification(new JsonRpcNotification("note", Collections.<String, Object>emptyMap()));
        transport.listener.onServerRequest(new JsonRpcRequest("1", "sampling", null));
        transport.listener.onFailure(new IllegalStateException("failure"));
    }

    private enum Answer { SUCCESS, NEVER, FAIL_RUNTIME, FAIL_CHECKED, NULL, ERROR }

    private static final class FakeTransport implements IMcpTransport {
        private Answer answer = Answer.SUCCESS;
        private IMcpTransportListener listener;
        private boolean cancelled;
        private boolean closed;

        public void start(IMcpTransportListener value) {
            this.listener = value;
        }

        public CompletableFuture<JsonRpcResponse> sendRequest(JsonRpcRequest request, long timeoutMillis) {
            CompletableFuture<JsonRpcResponse> future = new CompletableFuture<>();
            if (answer == Answer.NEVER) {
                return future;
            }
            if (answer == Answer.FAIL_RUNTIME) {
                future.completeExceptionally(new IllegalStateException("boom"));
                return future;
            }
            if (answer == Answer.FAIL_CHECKED) {
                future.completeExceptionally(new IOException("io"));
                return future;
            }
            if (answer == Answer.NULL) {
                future.complete(null);
                return future;
            }
            if (answer == Answer.ERROR) {
                future.complete(new JsonRpcResponse(request.getId(), null,
                        new JsonRpcError(-32603, "internal", null)));
                return future;
            }
            Map<String, Object> serverInfo = new LinkedHashMap<>();
            serverInfo.put("name", "fake");
            serverInfo.put("version", "1.0.0");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("protocolVersion", VERSION);
            result.put("serverInfo", serverInfo);
            result.put("capabilities", new LinkedHashMap<String, Object>());
            future.complete(new JsonRpcResponse(request.getId(), result, null));
            return future;
        }

        public void sendNotification(JsonRpcNotification notification) { }

        public boolean cancel(String requestId) {
            cancelled = true;
            return true;
        }

        public void checkHealth() { }

        public void close() {
            closed = true;
        }
    }
}