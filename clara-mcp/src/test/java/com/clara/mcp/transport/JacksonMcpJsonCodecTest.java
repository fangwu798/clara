package com.clara.mcp.transport;

import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCodes;
import com.clara.mcp.client.JsonRpcNotification;
import com.clara.mcp.client.JsonRpcRequest;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class JacksonMcpJsonCodecTest {
    private final JacksonMcpJsonCodec codec = new JacksonMcpJsonCodec();

    @Test
    public void encodesAndDecodesAllEnvelopeKinds() {
        String requestJson = codec.encode(new JsonRpcRequest("7", "tools/list",
                Collections.<String, Object>singletonMap("cursor", "next")));
        McpDecodedMessage request = codec.decode(requestJson);
        assertEquals(McpDecodedMessage.Type.SERVER_REQUEST, request.getType());
        assertEquals("7", request.getRequest().getId());

        String notificationJson = codec.encode(new JsonRpcNotification(
                "notifications/initialized", Collections.<String, Object>emptyMap()));
        assertEquals(McpDecodedMessage.Type.NOTIFICATION, codec.decode(notificationJson).getType());

        McpDecodedMessage success = codec.decode(
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"ok\":true}}");
        assertEquals(Boolean.TRUE, success.getResponse().getResult().get("ok"));
        assertNull(success.getResponse().getError());

        McpDecodedMessage error = codec.decode(
                "{\"jsonrpc\":\"2.0\",\"id\":\"7\",\"error\":{\"code\":-1,\"message\":\"bad\"}}");
        assertEquals(-1, error.getResponse().getError().getCode());
    }

    @Test
    public void rejectsMalformedEnvelopeWithStableCode() {
        try {
            codec.decode("{\"jsonrpc\":\"1.0\"}");
            fail("expected invalid message");
        } catch (ClaraException exception) {
            assertEquals(ErrorCodes.MCP_INVALID_MESSAGE, exception.getError().getCode());
        }
    }
}
