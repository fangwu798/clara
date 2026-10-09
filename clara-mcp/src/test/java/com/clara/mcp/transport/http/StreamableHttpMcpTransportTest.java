package com.clara.mcp.transport.http;

import com.clara.mcp.transport.JacksonMcpJsonCodec;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link StreamableHttpMcpTransport} 的构造期配置校验测试：只构建不发送，不连接真实 URL。
 */
public class StreamableHttpMcpTransportTest {

    @Test
    public void endpointHostMustBeAllowlisted() {
        assertIllegalArgument(new Runnable() {
            public void run() {
                builder("https://mcp.example.com/rpc", hosts("other.example.com")).build();
            }
        });
    }

    @Test
    public void plainHttpRejectedUnlessAllowed() {
        assertIllegalArgument(new Runnable() {
            public void run() {
                builder("http://mcp.example.com/rpc", hosts("mcp.example.com")).build();
            }
        });

        StreamableHttpMcpTransport transport = builder("http://mcp.example.com/rpc",
                hosts("mcp.example.com")).allowHttp(true).build();
        transport.close();
    }

    @Test
    public void codecRequired() {
        assertIllegalArgument(new Runnable() {
            public void run() {
                builder("https://mcp.example.com/rpc", hosts("mcp.example.com")).codec(null).build();
            }
        });
    }

    @Test
    public void limitsMustBePositive() {
        assertIllegalArgument(new Runnable() {
            public void run() {
                builder("https://mcp.example.com/rpc", hosts("mcp.example.com"))
                        .connectTimeoutMillis(0).build();
            }
        });
        assertIllegalArgument(new Runnable() {
            public void run() {
                builder("https://mcp.example.com/rpc", hosts("mcp.example.com"))
                        .maxResponseBytes(0).build();
            }
        });
    }

    @Test
    public void closeWithoutStartIsSafe() {
        StreamableHttpMcpTransport transport =
                builder("https://mcp.example.com/rpc", hosts("mcp.example.com")).build();

        transport.close();
        transport.close();
        assertTrue(true);
    }

    private static StreamableHttpMcpTransport.Builder builder(String endpoint, Set<String> allowedHosts) {
        return StreamableHttpMcpTransport.builder()
                .endpoint(URI.create(endpoint))
                .allowedHosts(allowedHosts)
                .codec(new JacksonMcpJsonCodec());
    }

    private static Set<String> hosts(String... values) {
        Set<String> hosts = new LinkedHashSet<String>();
        for (String value : values) {
            hosts.add(value);
        }
        return hosts;
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
