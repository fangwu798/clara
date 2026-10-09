package com.clara.mcp.registry.config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link McpServerConfigDirectoryLoader} 的行为测试：多文件合并、覆盖规则、递归扫描与非 json 忽略。
 */
public class McpServerConfigDirectoryLoaderTest {
    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private final McpServerConfigLoader configLoader = new McpServerConfigLoader();

    @Test
    public void mergesAllJsonFilesRecursively() throws IOException {
        write(folder.getRoot(), "a.json", stdioJson("alpha", "node", "alpha"));
        File nested = folder.newFolder("nested");
        write(nested, "b.json", stdioJson("beta", "node", "beta"));

        List<McpServerConfig> configs = runtimeOnly().load();

        assertEquals(2, configs.size());
        assertContains(configs, "alpha");
        assertContains(configs, "beta");
    }

    @Test
    public void laterFileOverridesEarlierForSameServerKey() throws IOException {
        write(folder.getRoot(), "a.json", stdioJson("shared", "node", "first"));
        write(folder.getRoot(), "b.json", stdioJson("shared", "node", "second"));

        List<McpServerConfig> configs = runtimeOnly().load();

        assertEquals(1, configs.size());
        assertEquals("second", configs.get(0).getCommand().get(1));
    }

    @Test
    public void ignoresNonJsonFiles() throws IOException {
        write(folder.getRoot(), "a.json", stdioJson("alpha", "node", "alpha"));
        write(folder.getRoot(), "notes.txt", "{ \"mcpServers\": { \"ignored\": { \"command\": \"node\" } } }");

        List<McpServerConfig> configs = runtimeOnly().load();

        assertEquals(1, configs.size());
        assertEquals("alpha", configs.get(0).getServerKey());
    }

    @Test
    public void missingDirectoryYieldsEmptyList() {
        List<McpServerConfig> configs = new McpServerConfigDirectoryLoader(
                configLoader, folder.getRoot().toPath().resolve("does-not-exist")).load();

        assertTrue(configs.isEmpty());
    }

    @Test
    public void runtimeDirectoryOverridesClasspathBuiltin() throws IOException {
        // classpath builtin: config/mcp/builtin.json 定义 serverKey=builtin
        write(folder.getRoot(), "builtin.json", stdioJson("builtin", "node", "runtime"));
        write(folder.getRoot(), "extra.json", stdioJson("extra", "node", "extra"));

        McpServerConfigDirectoryLoader loader = new McpServerConfigDirectoryLoader(
                configLoader, folder.getRoot().toPath(), true);
        List<McpServerConfig> configs = loader.load();

        assertContains(configs, "builtin");
        assertContains(configs, "extra");
        McpServerConfig builtin = find(configs, "builtin");
        assertEquals("runtime", builtin.getCommand().get(1));
    }

    private McpServerConfigDirectoryLoader runtimeOnly() {
        return new McpServerConfigDirectoryLoader(configLoader, folder.getRoot().toPath(), false);
    }

    private static void write(File directory, String name, String content) throws IOException {
        Files.write(directory.toPath().resolve(name), content.getBytes(StandardCharsets.UTF_8));
    }

    private static String stdioJson(String serverKey, String command, String arg) {
        return "{ \"mcpServers\": { \"" + serverKey + "\": { \"command\": \"" + command
                + "\", \"args\": [\"" + arg + "\"] } } }";
    }

    private static McpServerConfig find(List<McpServerConfig> configs, String serverKey) {
        for (McpServerConfig config : configs) {
            if (serverKey.equals(config.getServerKey())) { return config; }
        }
        throw new AssertionError("missing server: " + serverKey);
    }

    private static void assertContains(List<McpServerConfig> configs, String serverKey) {
        boolean found = false;
        for (McpServerConfig config : configs) {
            if (serverKey.equals(config.getServerKey())) { found = true; }
        }
        assertTrue("expected server " + serverKey, found);
    }
}
