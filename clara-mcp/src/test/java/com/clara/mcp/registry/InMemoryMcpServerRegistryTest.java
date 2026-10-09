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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link InMemoryMcpServerRegistry} 的注册 / 查取 / 状态 / 列表与并发行为测试。
 */
public class InMemoryMcpServerRegistryTest {
    private static final String TENANT = "tenant-1";
    private static final String OTHER_TENANT = "tenant-2";

    private final InMemoryMcpServerRegistry registry = new InMemoryMcpServerRegistry();

    @Test
    public void registerThenGetReturnsRegistration() {
        McpServerRegistration registration = registration(TENANT, "echo", McpRegistrationStatus.ACTIVE);

        registry.register(registration);

        assertSame(registration, registry.get(TENANT, "echo"));
    }

    @Test
    public void getMissingReturnsNull() {
        assertNull(registry.get(TENANT, "missing"));
    }

    @Test
    public void registerIsTenantScoped() {
        registry.register(registration(TENANT, "echo", McpRegistrationStatus.ACTIVE));
        registry.register(registration(OTHER_TENANT, "echo", McpRegistrationStatus.ACTIVE));

        assertEquals(TENANT, registry.get(TENANT, "echo").getTenantId());
        assertEquals(OTHER_TENANT, registry.get(OTHER_TENANT, "echo").getTenantId());
    }

    @Test
    public void duplicateRegisterThrowsDomainError() {
        registry.register(registration(TENANT, "echo", McpRegistrationStatus.ACTIVE));

        assertCode(ErrorCodes.MCP_SERVER_ALREADY_REGISTERED, new Runnable() {
            public void run() {
                registry.register(registration(TENANT, "echo", McpRegistrationStatus.ACTIVE));
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
        registry.register(registration(TENANT, "echo", McpRegistrationStatus.PENDING_REVIEW));

        registry.updateStatus(TENANT, "echo", McpRegistrationStatus.ACTIVE);

        assertSame(McpRegistrationStatus.ACTIVE, registry.get(TENANT, "echo").getStatus());
    }

    @Test
    public void updateStatusOnMissingThrowsDomainError() {
        assertCode(ErrorCodes.MCP_SERVER_NOT_REGISTERED, new Runnable() {
            public void run() {
                registry.updateStatus(TENANT, "missing", McpRegistrationStatus.ACTIVE);
            }
        });
    }

    @Test
    public void listEnabledFiltersByTenantAndStatusAndSorts() {
        registry.register(registration(TENANT, "b", McpRegistrationStatus.ACTIVE));
        registry.register(registration(TENANT, "a", McpRegistrationStatus.ACTIVE));
        registry.register(registration(TENANT, "c", McpRegistrationStatus.DISABLED));
        registry.register(registration(OTHER_TENANT, "d", McpRegistrationStatus.ACTIVE));

        List<McpServerRegistration> enabled = registry.listEnabled(TENANT);

        assertEquals(2, enabled.size());
        assertEquals("a", enabled.get(0).getServerKey());
        assertEquals("b", enabled.get(1).getServerKey());
    }

    @Test
    public void listEnabledReturnsReadOnlyList() {
        registry.register(registration(TENANT, "a", McpRegistrationStatus.ACTIVE));

        try {
            registry.listEnabled(TENANT).add(registration(TENANT, "x", McpRegistrationStatus.ACTIVE));
            fail("list must be read-only");
        } catch (UnsupportedOperationException expected) {
            assertTrue(true);
        }
    }

    @Test
    public void concurrentRegistrationIsThreadSafe() throws Exception {
        final int threads = 8;
        final int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<Future<Void>>();
        for (int thread = 0; thread < threads; thread++) {
            final int id = thread;
            futures.add(pool.submit(new Callable<Void>() {
                public Void call() throws Exception {
                    start.await();
                    for (int index = 0; index < perThread; index++) {
                        registry.register(registration(TENANT, "srv-" + id + "-" + index,
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

        assertEquals(threads * perThread, registry.listEnabled(TENANT).size());
        assertEquals("srv-0-0", registry.listEnabled(TENANT).get(0).getServerKey());
    }

    private static McpServerRegistration registration(String tenantId, String serverKey,
            McpRegistrationStatus status) {
        return new McpServerRegistration(tenantId, serverKey, McpTransportProfile.STDIO, "node echo", null,
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
