package com.clara.mcp.client.call;

import com.clara.context.CallContext;
import com.clara.context.CancellationToken;
import com.clara.context.ConvContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * MCP 调用入参与结果值对象的构造校验与不可变性测试。
 */
public class McpValueObjectsTest {
    private static final String TENANT = "tenant-1";
    private static final String SERVER = "echo-server";
    private static final String SNAPSHOT = "snapshot-1";
    private static final String HASH = "hash-1";

    @Test
    public void toolCallRejectsBlankIdentifiers() {
        assertIllegalArgument(new Runnable() {
            public void run() { newToolCall(" ", SERVER, SNAPSHOT, "echo", HASH); }
        });
        assertIllegalArgument(new Runnable() {
            public void run() { newToolCall(TENANT, "", SNAPSHOT, "echo", HASH); }
        });
        assertIllegalArgument(new Runnable() {
            public void run() { newToolCall(TENANT, SERVER, "  ", "echo", HASH); }
        });
        assertIllegalArgument(new Runnable() {
            public void run() { newToolCall(TENANT, SERVER, SNAPSHOT, "", HASH); }
        });
        assertIllegalArgument(new Runnable() {
            public void run() { newToolCall(TENANT, SERVER, SNAPSHOT, "echo", " "); }
        });
    }

    @Test
    public void toolCallRejectsNullContexts() {
        assertIllegalArgument(new Runnable() {
            public void run() {
                new McpToolCall(TENANT, SERVER, SNAPSHOT, "echo", HASH, null, null, call());
            }
        });
        assertIllegalArgument(new Runnable() {
            public void run() {
                new McpToolCall(TENANT, SERVER, SNAPSHOT, "echo", HASH, null, conversation(), null);
            }
        });
    }

    @Test
    public void toolCallCopiesArgumentsToReadOnlyView() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("text", "hi");
        McpToolCall call = new McpToolCall(TENANT, SERVER, SNAPSHOT, "echo", HASH, source,
                conversation(), call());
        source.put("text", "changed");

        assertEquals("hi", call.getArguments().get("text"));
        Map<String, Object> arguments = call.getArguments();
        assertThrows(UnsupportedOperationException.class, () -> arguments.put("x", "y"));
    }

    @Test
    public void resourceRequestValidatesAndExposesFields() {
        McpResourceRequest request = new McpResourceRequest(TENANT, SERVER, SNAPSHOT, "file://a.txt", HASH,
                conversation(), call());
        assertEquals("file://a.txt", request.getUri());
        assertEquals(HASH, request.getSchemaHash());
        assertIllegalArgument(new Runnable() {
            public void run() {
                new McpResourceRequest(TENANT, SERVER, SNAPSHOT, " ", HASH, conversation(), call());
            }
        });
    }

    @Test
    public void promptRequestCopiesArguments() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("topic", "java");
        McpPromptRequest request = new McpPromptRequest(TENANT, SERVER, SNAPSHOT, "explain", HASH, source,
                conversation(), call());
        source.put("topic", "other");
        assertEquals("java", request.getArguments().get("topic"));
        assertIllegalArgument(new Runnable() {
            public void run() {
                new McpPromptRequest(TENANT, SERVER, SNAPSHOT, " ", HASH, null, conversation(), call());
            }
        });
    }

    @Test
    public void promptResultRejectsNullMessages() {
        assertIllegalArgument(new Runnable() {
            public void run() { new McpPromptResult("desc", null, false); }
        });
        List<Map<String, Object>> withNull = new ArrayList<>();
        withNull.add(null);
        assertIllegalArgument(new Runnable() {
            public void run() { new McpPromptResult("desc", withNull, false); }
        });
    }

    @Test
    public void promptResultExposesContentAndErrorFlag() {
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        messages.add(message);
        McpPromptResult result = new McpPromptResult("desc", messages, true);
        assertEquals("desc", result.getDescription());
        assertEquals(1, result.getMessages().size());
        assertTrue(result.isError());
    }

    private static McpToolCall newToolCall(String tenantId, String serverKey, String snapshotId,
            String toolName, String schemaHash) {
        return new McpToolCall(tenantId, serverKey, snapshotId, toolName, schemaHash, null,
                conversation(), call());
    }

    private static ConvContext conversation() {
        return new ConvContext("user-1", "conv-1", null, null);
    }

    private static CallContext call() {
        return new CallContext("trace-1", System.currentTimeMillis() + 10000L, new CancellationToken());
    }

    private static void assertIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(true);
        }
    }
}
