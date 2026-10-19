package com.clara.mcp.client;

import com.clara.exception.ClaraException;
import com.clara.exception.ErrorCodes;
import com.clara.mcp.api.IMcpClient;
import com.clara.mcp.client.DefaultMcpClient;
import com.clara.mcp.registry.McpRegistrationStatus;
import com.clara.mcp.registry.McpServerRegistration;
import com.clara.mcp.registry.McpTransportProfile;
import com.clara.mcp.transport.JacksonMcpJsonCodec;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
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
        IMcpClient client = factory.create(registration("http-server",
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
        IMcpClient client = factory.create(registration("stdio-server",
                McpTransportProfile.STDIO, base.toString()));

        assertNotNull(client);
        assertTrue(client instanceof DefaultMcpClient);
        client.close();
    }

    @Test
    public void bareCommandNameIsResolvedFromAllowList() throws Exception {
        // MCP 生态的配置惯例是写裸命令名（npx / node），而 stdio 传输只接受绝对路径：
        // 工厂应把首项换成允许清单里的绝对路径，否则建连前就会以「executable must be absolute」失败。
        Path base = Paths.get(".").toRealPath();
        String bare = base.getFileName().toString();
        Assume.assumeFalse("working directory must not contain spaces for command parsing",
                base.toString().contains(" "));
        DefaultMcpClientFactory factory = new DefaultMcpClientFactory(new JacksonMcpJsonCodec(), null,
                Collections.singletonList(base), base, null, 1000L);
        List<String> command = Collections.singletonList(bare);

        IMcpClient client = factory.create(registrationWithCommand(bare, command));

        assertNotNull(client);
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

    private static McpServerRegistration registrationWithCommand(String serverKey, List<String> command) {
        return new McpServerRegistration(TENANT, serverKey, McpTransportProfile.STDIO, serverKey,
                null, null, null, McpRegistrationStatus.ACTIVE, command, null, null);
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
