package com.clara.skill.executor;

import com.clara.core.skill.IToolInvoker;
import com.clara.core.tool.IToolInvocation;
import com.clara.dto.tool.ToolResult;
import com.clara.enums.skill.SkillExecutionStatusEnum;
import com.clara.exception.ClaraException;
import com.clara.skill.registry.SkillRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Skill-level execution orchestration: success, timeout, cancellation, permission and schema failures.
 */
public class SkillExecutorTest {

    private static final String TOOL_JSON = "{\"id\":\"echo\",\"name\":\"echo\",\"type\":\"PYTHON\","
            + "\"description\":\"echo tool\",\"parameters\":\"{\\\"type\\\":\\\"object\\\","
            + "\\\"properties\\\":{\\\"text\\\":{\\\"type\\\":\\\"string\\\"}},\\\"required\\\":[\\\"text\\\"]}\","
            + "\"script\":\"print(1)\"}";

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private SkillRegistry registry;

    @Before
    public void setUp() throws IOException {
        File root = folder.newFolder("skill");
        File tools = new File(root, "tools");
        assertTrue(tools.mkdirs());
        Files.write(new File(tools, "echo.json").toPath(), TOOL_JSON.getBytes(StandardCharsets.UTF_8));
        registry = new SkillRegistry(null, root, null, null, null);
        registry.reload();
    }

    @After
    public void tearDown() {
        registry = null;
    }

    @Test
    public void executesSynchronously() {
        SkillExecutor executor = new SkillExecutor(registry, invoker("{\"ok\":true}"));
        assertEquals("{\"ok\":true}", executor.executeSync("echo", "{\"text\":\"hi\"}"));
    }

    @Test
    public void executesThroughHandleAndResume() throws Exception {
        SkillExecutor executor = new SkillExecutor(registry, invoker("{\"ok\":true}"));
        SkillExecutionHandle handle = executor.submit("echo", "{\"text\":\"hi\"}");
        assertTrue(handle.await(2L, TimeUnit.SECONDS));
        assertEquals(SkillExecutionStatusEnum.COMPLETED, handle.status());
        assertEquals("{\"ok\":true}", handle.result());

        SkillExecutionHandle resumed = executor.resume("echo", "{\"text\":\"hi\"}");
        assertTrue(resumed.await(2L, TimeUnit.SECONDS));
        assertEquals("{\"ok\":true}", resumed.result());
    }

    @Test
    public void timesOutAndCancelsExecution() throws Exception {
        SkillExecutor executor = new SkillExecutor(registry, slowInvoker(300L, "{\"ok\":true}"));
        try {
            executor.executeSync("echo", "{\"text\":\"hi\"}", 20L);
            fail("expected timeout");
        } catch (ClaraException expected) {
            assertEquals("SKILL_TIMEOUT", expected.getError().getCode());
        }
    }

    @Test
    public void reportsUnknownTool() {
        SkillExecutor executor = new SkillExecutor(registry, invoker("{\"ok\":true}"));
        try {
            executor.executeSync("missing", "{}");
            fail("expected not found");
        } catch (ClaraException expected) {
            assertEquals("SKILL_NOT_FOUND", expected.getError().getCode());
        }
    }

    @Test
    public void enforcesPermissionWhitelist() {
        SkillExecutor executor = new SkillExecutor(registry, invoker("{\"ok\":true}"),
                new SkillSchemaValidator(), new SkillPermissionAuthorizer(Collections.singletonList("other")));
        try {
            executor.executeSync("echo", "{\"text\":\"hi\"}");
            fail("expected permission denied");
        } catch (ClaraException expected) {
            assertEquals("SKILL_PERMISSION_DENIED", expected.getError().getCode());
        }
    }

    @Test
    public void rejectsInvalidInputAndOutput() {
        SkillExecutor badInput = new SkillExecutor(registry, invoker("{\"ok\":true}"));
        try {
            badInput.executeSync("echo", "{}");
            fail("expected schema error");
        } catch (ClaraException expected) {
            assertEquals("SKILL_SCHEMA_INVALID", expected.getError().getCode());
        }

        SkillExecutor badOutput = new SkillExecutor(registry, invoker("not-json"));
        try {
            badOutput.executeSync("echo", "{\"text\":\"hi\"}");
            fail("expected schema error");
        } catch (ClaraException expected) {
            assertEquals("SKILL_SCHEMA_INVALID", expected.getError().getCode());
        }
    }

    @Test
    public void cancellationDuringToolInvocationEndsAsCancelled() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        IToolInvoker blocking = new IToolInvoker() {
            @Override
            public ToolResult invoke(IToolInvocation invocation) {
                started.countDown();
                try {
                    release.await(2L, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new ToolResult(invocation.callId(), "{\"ok\":true}");
            }
        };
        SkillExecutor executor = new SkillExecutor(registry, blocking);
        SkillExecutionHandle handle = executor.submit("echo", "{\"text\":\"hi\"}");
        assertTrue(started.await(2L, TimeUnit.SECONDS));
        handle.cancel();
        release.countDown();
        assertTrue(handle.await(2L, TimeUnit.SECONDS));
        assertEquals(SkillExecutionStatusEnum.CANCELLED, handle.status());
        try {
            handle.resultOrThrow();
            fail("expected cancellation");
        } catch (ClaraException expected) {
            assertEquals("INVOCATION_CANCELLED", expected.getError().getCode());
        }
    }

    @Test
    public void failureIsDeliveredThroughHandle() throws Exception {
        IToolInvoker failing = new IToolInvoker() {
            @Override
            public ToolResult invoke(IToolInvocation invocation) {
                throw new IllegalStateException("tool boom");
            }
        };
        SkillExecutionHandle handle = new SkillExecutor(registry, failing).submit("echo", "{\"text\":\"hi\"}");
        assertTrue(handle.await(2L, TimeUnit.SECONDS));
        assertEquals(SkillExecutionStatusEnum.FAILED, handle.status());
        try {
            handle.resultOrThrow();
            fail("expected failure");
        } catch (IllegalStateException expected) {
            assertEquals("tool boom", expected.getMessage());
        }
    }

    @Test
    public void validatesConstructorAndFallsBackToDefaultCollaborators() {
        assertIllegalArgument(() -> new SkillExecutor(null, invoker("{}")));
        assertIllegalArgument(() -> new SkillExecutor(registry, null));
        SkillExecutor executor = new SkillExecutor(registry, invoker("{\"ok\":true}"), null, null);
        assertEquals("{\"ok\":true}", executor.executeSync("echo", "{\"text\":\"hi\"}"));
    }

    private static IToolInvoker invoker(String output) {
        return new IToolInvoker() {
            @Override
            public ToolResult invoke(IToolInvocation invocation) {
                return new ToolResult(invocation.callId(), output);
            }
        };
    }

    private static IToolInvoker slowInvoker(long millis, String output) {
        return new IToolInvoker() {
            @Override
            public ToolResult invoke(IToolInvocation invocation) {
                try {
                    Thread.sleep(millis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new ToolResult(invocation.callId(), output);
            }
        };
    }

    private static void assertIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
