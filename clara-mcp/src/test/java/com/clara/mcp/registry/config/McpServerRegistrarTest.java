package com.clara.mcp.registry.config;

import com.clara.mcp.registry.InMemoryMcpServerRegistry;
import com.clara.mcp.registry.McpRegistrationStatus;
import com.clara.mcp.registry.McpServerRegistration;
import com.clara.mcp.registry.McpTransportProfile;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link McpServerRegistrar} 的行为测试：config → registration 字段映射与批量注册落地。
 */
public class McpServerRegistrarTest {
    private static final String TENANT = "tenant-1";

    private final InMemoryMcpServerRegistry registry = new InMemoryMcpServerRegistry();
    private final McpServerRegistrar registrar = new McpServerRegistrar(registry);

    @Test
    public void stdioConfigMapsToRegistration() {
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("API_KEY", "secret");
        McpServerConfig config = McpServerConfig.of("processon",
                Arrays.asList("npx", "-y", "@processon/mcp-server-processon"), env, null, null);

        McpServerRegistration registration = registrar.toRegistration(TENANT, config);

        assertEquals(McpTransportProfile.STDIO, registration.getTransportProfile());
        assertEquals("npx -y @processon/mcp-server-processon", registration.getAddress());
        assertEquals(3, registration.getCommand().size());
        assertEquals("secret", registration.getEnv().get("API_KEY"));
        assertTrue(registration.getHeaders().isEmpty());
        assertEquals(McpRegistrationStatus.ACTIVE, registration.getStatus());
    }

    @Test
    public void httpConfigMapsToRegistration() {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Authorization", "Bearer xxx");
        McpServerConfig config = McpServerConfig.of("remote", null, null, "https://host/mcp", headers);

        McpServerRegistration registration = registrar.toRegistration(TENANT, config);

        assertEquals(McpTransportProfile.STREAMABLE_HTTP, registration.getTransportProfile());
        assertEquals("https://host/mcp", registration.getAddress());
        assertEquals("Bearer xxx", registration.getHeaders().get("Authorization"));
        assertTrue(registration.getCommand().isEmpty());
    }

    @Test
    public void registerAllRegistersEveryConfig() {
        McpServerConfig stdio = McpServerConfig.of("stdio-server",
                Collections.singletonList("node"), null, null, null);
        McpServerConfig http = McpServerConfig.of("http-server", null, null, "https://host/mcp", null);

        List<McpServerRegistration> registered = registrar.registerAll(TENANT, Arrays.asList(stdio, http));

        assertEquals(2, registered.size());
        assertNotNull(registry.get(TENANT, "stdio-server"));
        assertNotNull(registry.get(TENANT, "http-server"));
        assertEquals(2, registry.listEnabled(TENANT).size());
    }
}
