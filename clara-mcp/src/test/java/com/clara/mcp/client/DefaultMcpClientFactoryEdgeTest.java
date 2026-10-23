package com.clara.mcp.client;

import com.clara.mcp.api.IMcpClient;
import com.clara.mcp.api.IProcessFactory;
import com.clara.mcp.registry.McpRegistrationStatus;
import com.clara.mcp.registry.McpServerRegistration;
import com.clara.mcp.registry.McpTransportProfile;
import com.clara.mcp.transport.JacksonMcpJsonCodec;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link DefaultMcpClientFactory} 的配置边界测试。
 *
 * <p>为什么需要：工厂是配置错误的第一道闸门。空编解码器、空允许清单、非正超时如果不在构造期拦住，
 * 会拖到真正建连时才以难以理解的错误暴露；stdio 的 env / 自定义进程工厂、http 的固定请求头
 * 也都在这里分派，缺一个分支就等于配置项静默失效。
 */
public class DefaultMcpClientFactoryEdgeTest {

    @Test
    public void constructorRejectsInvalidCollaborators() throws Exception {
        Path base = Paths.get(".").toRealPath();
        expectConstructorFailure(null, Collections.singletonList(base), base, 1000L, "codec must not be null");
        expectConstructorFailure(new JacksonMcpJsonCodec(), Collections.<Path>emptyList(), base, 1000L,
                "allowedExecutables must not be empty");
        expectConstructorFailure(new JacksonMcpJsonCodec(), Collections.singletonList(base), null, 1000L,
                "workingDirectory must not be null");
        expectConstructorFailure(new JacksonMcpJsonCodec(), Collections.singletonList(base), base, 0L,
                "requestTimeoutMillis must be positive");
    }

    @Test
    public void stdioUsesEnvironmentAndCustomProcessFactory() throws Exception {
        Path base = Paths.get(".").toRealPath();
        Assume.assumeFalse("目录含空格时命令解析会歧义", base.toString().contains(" "));
        final boolean[] started = new boolean[1];
        IProcessFactory processFactory = new IProcessFactory() {
            public Process start(java.util.List<String> command, Path workingDirectory,
                    Map<String, String> environment) throws IOException {
                started[0] = true;
                throw new IOException("not started in unit test");
            }
        };
        Map<String, String> env = new LinkedHashMap<>();
        env.put("CLARA_TEST", "1");
        DefaultMcpClientFactory factory = new DefaultMcpClientFactory(new JacksonMcpJsonCodec(), processFactory,
                Collections.singletonList(base), base, null, 1000L);

        IMcpClient client = factory.create(registration(McpTransportProfile.STDIO, base.toString(),
                Collections.singletonList(base.toString()), env, null));

        assertNotNull(client);
        assertTrue("构造客户端不应启动进程", !started[0]);
        client.close();
    }

    @Test
    public void stdioFallsBackToAddressWhenCommandIsEmpty() throws Exception {
        Path base = Paths.get(".").toRealPath();
        Assume.assumeFalse("目录含空格时命令解析会歧义", base.toString().contains(" "));
        DefaultMcpClientFactory factory = new DefaultMcpClientFactory(new JacksonMcpJsonCodec(), null,
                Collections.singletonList(base), base, null, 1000L);

        IMcpClient client = factory.create(registration(McpTransportProfile.STDIO, base.toString(),
                Collections.<String>emptyList(), null, null));
        assertNotNull(client);
        client.close();
    }

    @Test
    public void unresolvableBareCommandStillFailsAtBuild() throws Exception {
        Path base = Paths.get(".").toRealPath();
        DefaultMcpClientFactory factory = new DefaultMcpClientFactory(new JacksonMcpJsonCodec(), null,
                Collections.singletonList(base), base, null, 1000L);
        try {
            factory.create(registration(McpTransportProfile.STDIO, "zzz-not-in-allow-list",
                    Collections.singletonList("zzz-not-in-allow-list"), null, null));
            fail("无法绝对化的命令必须在建连前报错");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("absolute"));
        }
    }

    @Test
    public void httpUsesRegistrationHeaders() throws Exception {
        DefaultMcpClientFactory factory = new DefaultMcpClientFactory(new JacksonMcpJsonCodec(), null,
                Collections.singletonList(Paths.get(".")), Paths.get("."), null, 1000L);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer test");
        IMcpClient client = factory.create(registration(McpTransportProfile.STREAMABLE_HTTP,
                "https://mcp.example.com/rpc", null, null, headers));
        assertNotNull(client);
        client.close();
    }

    @Test
    public void httpWithoutHeadersUsesProvidedProvider() throws Exception {
        DefaultMcpClientFactory factory = new DefaultMcpClientFactory(new JacksonMcpJsonCodec(), null,
                Collections.singletonList(Paths.get(".")), Paths.get("."),
                new com.clara.mcp.api.IMcpHeadersProvider() {
                    public Map<String, String> getHeaders() {
                        return Collections.singletonMap("X-Test", "1");
                    }
                }, 1000L);
        IMcpClient client = factory.create(registration(McpTransportProfile.STREAMABLE_HTTP,
                "https://mcp.example.com/rpc", null, null, null));
        assertNotNull(client);
        client.close();
    }

    private static void expectConstructorFailure(JacksonMcpJsonCodec codec, java.util.List<Path> allowed,
            Path workingDirectory, long timeout, String expected) {
        try {
            new DefaultMcpClientFactory(codec, null, allowed, workingDirectory, null, timeout);
            fail("expected failure: " + expected);
        } catch (IllegalArgumentException failure) {
            org.junit.Assert.assertEquals(expected, failure.getMessage());
        }
    }

    private static McpServerRegistration registration(McpTransportProfile profile, String address,
            java.util.List<String> command, Map<String, String> env, Map<String, String> headers) {
        return new McpServerRegistration("server-" + profile.name().toLowerCase(), profile, address,
                null, null, null, McpRegistrationStatus.ACTIVE, command, env, headers);
    }
}