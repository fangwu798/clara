package com.clara.mcp.registry.config;

import com.clara.mcp.registry.McpTransportProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link McpServerConfigLoader} 的行为测试：stdio / http 解析、env 插值与非法输入。
 */
public class McpServerConfigLoaderTest {
    private final Map<String, String> variables = new HashMap<String, String>();
    private final McpServerConfigLoader loader = new McpServerConfigLoader(new ObjectMapper(), key -> variables.get(key));

    @Test
    public void parsesStdioConfigWithCommandArgsAndEnv() {
        String json = "{ \"mcpServers\": { \"processon\": {"
                + " \"command\": \"npx\","
                + " \"args\": [\"-y\", \"@processon/mcp-server-processon\"],"
                + " \"env\": { \"PROCESSON_API_KEY\": \"secret\" } } } }";

        List<McpServerConfig> configs = loader.load(json);

        assertEquals(1, configs.size());
        McpServerConfig config = configs.get(0);
        assertEquals("processon", config.getServerKey());
        assertEquals(McpTransportProfile.STDIO, config.getTransportProfile());
        assertEquals(3, config.getCommand().size());
        assertEquals("npx", config.getCommand().get(0));
        assertEquals("@processon/mcp-server-processon", config.getCommand().get(2));
        assertEquals("secret", config.getEnv().get("PROCESSON_API_KEY"));
        assertNull(config.getUrl());
        assertTrue(config.getHeaders().isEmpty());
    }

    @Test
    public void parsesHttpConfigWithUrlAndHeaders() {
        String json = "{ \"mcpServers\": { \"remote\": {"
                + " \"url\": \"https://host/mcp\","
                + " \"headers\": { \"Authorization\": \"Bearer xxx\" } } } }";

        McpServerConfig config = loader.load(json).get(0);

        assertEquals(McpTransportProfile.STREAMABLE_HTTP, config.getTransportProfile());
        assertEquals("https://host/mcp", config.getUrl());
        assertEquals("Bearer xxx", config.getHeaders().get("Authorization"));
        assertTrue(config.getCommand().isEmpty());
    }

    @Test
    public void interpolatesEnvAndHeaderVariables() {
        variables.put("MCP_TOKEN", "resolved-token");
        String json = "{ \"mcpServers\": {"
                + " \"a\": { \"command\": \"node\", \"env\": { \"TOKEN\": \"${MCP_TOKEN}\" } },"
                + " \"b\": { \"url\": \"https://host/mcp\","
                + " \"headers\": { \"Authorization\": \"Bearer ${MCP_TOKEN}\" } } } }";

        List<McpServerConfig> configs = loader.load(json);

        assertEquals("resolved-token", configs.get(0).getEnv().get("TOKEN"));
        assertEquals("Bearer resolved-token", configs.get(1).getHeaders().get("Authorization"));
    }

    @Test
    public void undefinedVariableFailsWithVariableName() {
        String json = "{ \"mcpServers\": { \"a\": { \"command\": \"node\","
                + " \"env\": { \"TOKEN\": \"${MISSING_VAR}\" } } } }";

        try {
            loader.load(json);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("MISSING_VAR"));
        }
    }

    @Test
    public void missingMcpServersFails() {
        assertLoadFails("{ \"servers\": {} }", "mcpServers");
    }

    @Test
    public void blankServerKeyFails() {
        assertLoadFails("{ \"mcpServers\": { \"\": { \"command\": \"node\" } } }", "key");
    }

    @Test
    public void neitherCommandNorUrlFails() {
        assertLoadFails("{ \"mcpServers\": { \"a\": { \"env\": { \"X\": \"y\" } } } }", "command");
    }

    @Test
    public void bothCommandAndUrlFails() {
        assertLoadFails("{ \"mcpServers\": { \"a\": { \"command\": \"node\", \"url\": \"https://h/mcp\" } } }",
                "both");
    }

    @Test
    public void invalidJsonFails() {
        try {
            loader.load("{ not json");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("invalid mcp config json"));
        }
    }

    private void assertLoadFails(String json, String expectedFragment) {
        try {
            loader.load(json);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue("message should mention '" + expectedFragment + "' but was: " + expected.getMessage(),
                    expected.getMessage().contains(expectedFragment));
        }
    }
}
