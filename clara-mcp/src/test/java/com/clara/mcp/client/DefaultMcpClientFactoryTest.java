package com.clara.mcp.client;

import com.clara.exception.ClaraException;
import com.clara.exception.ErrorCodes;
import com.clara.mcp.client.DefaultMcpClient;
import com.clara.mcp.client.McpClient;
import com.clara.mcp.registry.McpRegistrationStatus;
import com.clara.mcp.registry.McpServerRegistration;
import com.clara.mcp.registry.McpTransportProfile;
import com.clara.mcp.transport.JacksonMcpJsonCodec;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link DefaultMcpClientFactory} 的行为测试：只验证按传输方式分派与配置校验，不启动进程、不连网络。
 */
public class DefaultMcpClientFactoryTest {
    private static final String TENANT = "tenant-1";

    @Test
    public void streamableHttpProfileCreatesClient() throws Exception {
        DefaultMcpClientFactory factory = factoryFor(Paths.get("."));
        McpClient client = factory.create(registration("http-server",
                McpTransportProfile.STREAMABLE_HTTP, "https://mcp.example.com/rpc"));

        assertNotNull(client);
        assertTrue(client instanceof DefaultMcpClient);
        client.close();
    }

    @Test
    public void stdioProfileCreatesClientWithoutStartingProcess() throws Exception {
        Path base = Paths.get(".").toRealPath();
        Assume.assumeFalse("working directory must not contain spaces for command parsing",
                base.toString().contains(" "));
        DefaultMcpClientFactory factory = factoryFor(base);
        McpClient client = factory.create(registration("stdio-server",
                McpTransportProfile.STDIO, base.toString()));

        assertNotNull(client);
        assertTrue(client instanceof DefaultMcpClient);
        client.close();
    }

    @Test
    public void unknownTransportProfileThrowsDomainError() {
        final DefaultMcpClientFactory factory = factoryFor(Paths.get("."));
        final McpServerRegistration registration = registration("sse-server",
                McpTransportProfile.LEGACY_SSE, "https://mcp.example.com/sse");

        assertCode(ErrorCodes.CAPABILITY_NOT_SUPPORTED, new Runnable() {
            public void run() { factory.create(registration); }
        });
    }

    @Test
    public void nullRegistrationRejected() {
        DefaultMcpClientFactory factory = factoryFor(Paths.get("."));
        try {
            factory.create(null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("registration"));
        }
    }

    private static DefaultMcpClientFactory factoryFor(Path workingDirectory) {
        return new DefaultMcpClientFactory(new JacksonMcpJsonCodec(), null,
                Collections.singletonList(workingDirectory), workingDirectory, null, 1000L);
    }

    private static McpServerRegistration registration(String serverKey, McpTransportProfile profile,
            String address) {
        return new McpServerRegistration(TENANT, serverKey, profile, address, null, null, null,
                McpRegistrationStatus.ACTIVE);
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
