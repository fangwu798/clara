package com.clara.mcp.transport;

import com.clara.exception.ClaraException;
import com.clara.exception.ErrorCodes;
import com.clara.mcp.transport.jsonrpc.JsonRpcError;
import com.clara.mcp.transport.jsonrpc.JsonRpcNotification;
import com.clara.mcp.transport.jsonrpc.JsonRpcRequest;
import com.clara.mcp.transport.jsonrpc.JsonRpcResponse;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link JacksonMcpJsonCodec} 的请求 / 响应 / 错误 / 通知编解码往返与非法报文测试。
 */
public class JacksonMcpJsonCodecTest {
    private final JacksonMcpJsonCodec codec = new JacksonMcpJsonCodec();

    @Test
    public void requestRoundTripsAsServerRequest() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", "2024-11-05");
        params.put("nested", Arrays.<Object>asList(1, "two"));

        McpDecodedMessage message = codec.decode(codec.encode(new JsonRpcRequest("id-1", "initialize", params)));

        assertSame(McpDecodedMessage.Type.SERVER_REQUEST, message.getType());
        assertEquals("id-1", message.getRequest().getId());
        assertEquals("initialize", message.getRequest().getMethod());
        assertEquals("2024-11-05", message.getRequest().getParams().get("protocolVersion"));
        assertEquals(Arrays.<Object>asList(1, "two"), message.getRequest().getParams().get("nested"));
    }

    @Test
    public void notificationRoundTripsAsNotification() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("status", "ok");

        McpDecodedMessage message = codec.decode(
                codec.encode(new JsonRpcNotification("notifications/initialized", params)));

        assertSame(McpDecodedMessage.Type.NOTIFICATION, message.getType());
        assertEquals("notifications/initialized", message.getNotification().getMethod());
        assertEquals("ok", message.getNotification().getParams().get("status"));
    }

    @Test
    public void decodeResponseWithResult() {
        McpDecodedMessage message = codec.decode("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"a\":1}}");

        assertSame(McpDecodedMessage.Type.RESPONSE, message.getType());
        assertEquals("1", message.getResponse().getId());
        assertEquals(1, message.getResponse().getResult().get("a"));
        assertNull(message.getResponse().getError());
    }

    @Test
    public void decodeResponseWithError() {
        McpDecodedMessage message = codec.decode(
                "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"error\":{\"code\":-32601,\"message\":\"m\",\"data\":{\"k\":\"v\"}}}");

        assertSame(McpDecodedMessage.Type.RESPONSE, message.getType());
        JsonRpcError error = message.getResponse().getError();
        assertEquals(-32601, error.getCode());
        assertEquals("m", error.getMessage());
        assertEquals("v", error.getData().get("k"));
        assertNull(message.getResponse().getResult());
    }

    @Test
    public void responseEnvelopeEncodesJsonRpcVersion() {
        String json = codec.encode(new JsonRpcRequest("1", "ping", null));

        assertTrue(json.contains("\"jsonrpc\":\"2.0\""));
        assertTrue(json.contains("\"method\":\"ping\""));
        assertFalse(json.contains("result"));
    }

    @Test
    public void invalidJsonRejected() {
        assertCode(ErrorCodes.MCP_INVALID_MESSAGE, new Runnable() {
            public void run() { codec.decode("not json"); }
        });
    }

    @Test
    public void blankJsonRejected() {
        assertCode(ErrorCodes.MCP_INVALID_MESSAGE, new Runnable() {
            public void run() { codec.decode("   "); }
        });
    }

    @Test
    public void missingJsonRpcVersionRejected() {
        assertCode(ErrorCodes.MCP_INVALID_MESSAGE, new Runnable() {
            public void run() { codec.decode("{\"id\":\"1\",\"result\":{}}"); }
        });
    }

    @Test
    public void resultAndErrorTogetherRejected() {
        assertCode(ErrorCodes.MCP_INVALID_MESSAGE, new Runnable() {
            public void run() {
                codec.decode("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{},"
                        + "\"error\":{\"code\":1,\"message\":\"m\"}}");
            }
        });
    }

    @Test
    public void responseWithoutIdRejected() {
        assertCode(ErrorCodes.MCP_INVALID_MESSAGE, new Runnable() {
            public void run() { codec.decode("{\"jsonrpc\":\"2.0\",\"result\":{}}"); }
        });
    }

    @Test
    public void nullEncodeArgumentsRejected() {
        try {
            codec.encode((JsonRpcRequest) null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(true);
        }
        try {
            codec.encode((JsonRpcNotification) null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(true);
        }
    }

    @Test
    public void nullMapperRejected() {
        try {
            new JacksonMcpJsonCodec(null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(true);
        }
    }

    private static void assertCode(String expectedCode, Runnable action) {
        try {
            action.run();
            fail("expected ClaraException with code " + expectedCode);
        } catch (ClaraException exception) {
            assertEquals(expectedCode, exception.getError().getCode());
        }
    }
}
