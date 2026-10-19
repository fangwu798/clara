package com.clara.mcp.registry;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertSame;

/**
 * 注册 / 传输 / 健康三个枚举的取值轻量断言。
 */
public class McpRegistryEnumsTest {

    @Test
    public void transportProfileValues() {
        assertEquals(3, McpTransportProfile.values().length);
        assertEquals(McpTransportProfile.STDIO, McpTransportProfile.valueOf("STDIO"));
        assertEquals(McpTransportProfile.STREAMABLE_HTTP, McpTransportProfile.valueOf("STREAMABLE_HTTP"));
        assertEquals(McpTransportProfile.LEGACY_SSE, McpTransportProfile.valueOf("LEGACY_SSE"));
    }

    @Test
    public void registrationStatusValues() {
        assertEquals(5, McpRegistrationStatus.values().length);
        assertSame(McpRegistrationStatus.ACTIVE, McpRegistrationStatus.valueOf("ACTIVE"));
        assertSame(McpRegistrationStatus.INCOMPATIBLE, McpRegistrationStatus.valueOf("INCOMPATIBLE"));
        assertSame(McpRegistrationStatus.REVIEW_REQUIRED, McpRegistrationStatus.valueOf("REVIEW_REQUIRED"));
        assertSame(McpRegistrationStatus.DISABLED, McpRegistrationStatus.valueOf("DISABLED"));
        assertSame(McpRegistrationStatus.PENDING_REVIEW, McpRegistrationStatus.valueOf("PENDING_REVIEW"));
    }

    @Test
    public void healthStatusValues() {
        assertEquals(3, McpHealthStatus.values().length);
        assertEquals(McpHealthStatus.HEALTHY, McpHealthStatus.valueOf("HEALTHY"));
        assertEquals(McpHealthStatus.DEGRADED, McpHealthStatus.valueOf("DEGRADED"));
        assertEquals(McpHealthStatus.UNHEALTHY, McpHealthStatus.valueOf("UNHEALTHY"));
    }
}
