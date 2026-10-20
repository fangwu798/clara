package com.clara.mcp.registry;

import com.clara.exception.ClaraException;
import com.clara.exception.ErrorCodes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link InMemoryMcpServerRegistry} 的注册 / 查取 / 状态 / 列表与并发行为测试。
 */
public class InMemoryMcpServerRegistryTest {
    private final InMemoryMcpServerRegistry registry = new InMemoryMcpServerRegistry();

    @Test
    public void registerThenGetReturnsRegistration() {
        McpServerRegistration registration = registration("echo", McpRegistrationStatus.ACTIVE);

        registry.register(registration);

        assertSame(registration, registry.get("echo"));
    }

    @Test
    public void getMissingReturnsNull() {
        assertNull(registry.get("missing"));
    }

    @Test
    public void duplicateRegisterThrowsDomainError() {
        registry.register(registration("echo", McpRegistrationStatus.ACTIVE));

        assertCode(ErrorCodes.MCP_SERVER_ALREADY_REGISTERED, new Runnable() {
            public void run() {
                registry.register(registration("echo", McpRegistrationStatus.ACTIVE));
            }
        });
    }

    @Test
    public void nullRegistrationRejected() {
        try {
            registry.register(null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("registration"));
        }
    }

    @Test
    public void updateStatusReplacesStatus() {
        registry.register(registration("echo", McpRegistrationStatus.PENDING_REVIEW));

        registry.updateStatus("echo", McpRegistrationStatus.ACTIVE);

        assertSame(McpRegistrationStatus.ACTIVE, registry.get("echo").getStatus());
    }

    @Test
    public void updateStatusOnMissingThrowsDomainError() {
        assertCode(ErrorCodes.MCP_SERVER_NOT_REGISTERED, new Runnable() {
            public void run() {
                registry.updateStatus("missing", McpRegistrationStatus.ACTIVE);
            }
        });
    }

    @Test
    public void listEnabledFiltersByStatusAndSorts() {
        registry.register(registration("b", McpRegistrationStatus.ACTIVE));
        registry.register(registration("a", McpRegistrationStatus.ACTIVE));
        registry.register(registration("c", McpRegistrationStatus.DISABLED));
        registry.register(registration("d", McpRegistrationStatus.ACTIVE));

        List<McpServerRegistration> enabled = registry.listEnabled();

        assertEquals(3, enabled.size());
        assertEquals("a", enabled.get(0).getServerKey());
        assertEquals("b", enabled.get(1).getServerKey());
        assertEquals("d", enabled.get(2).getServerKey());
    }

    @Test
    public void listEnabledReturnsReadOnlyList() {
        registry.register(registration("a", McpRegistrationStatus.ACTIVE));

        List<McpServerRegistration> enabled = registry.listEnabled();
        McpServerRegistration extra = registration("x", McpRegistrationStatus.ACTIVE);
        assertThrows(UnsupportedOperationException.class, () -> enabled.add(extra));
    }

    @Test
    public void concurrentRegistrationIsThreadSafe() throws Exception {
        final int threads = 8;
        final int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        for (int thread = 0; thread < threads; thread++) {
            final int id = thread;
            futures.add(pool.submit(new Callable<Void>() {
                public Void call() throws Exception {
                    start.await();
                    for (int index = 0; index < perThread; index++) {
                        registry.register(registration("srv-" + id + "-" + index,
                                McpRegistrationStatus.ACTIVE));
                    }
                    return null;
                }
            }));
        }
        start.countDown();
        for (Future<Void> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertEquals(threads * perThread, registry.listEnabled().size());
        assertEquals("srv-0-0", registry.listEnabled().get(0).getServerKey());
    }

    private static McpServerRegistration registration(String serverKey,
            McpRegistrationStatus status) {
        return new McpServerRegistration(serverKey, McpTransportProfile.STDIO, "node echo", null,
                null, null, status);
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
