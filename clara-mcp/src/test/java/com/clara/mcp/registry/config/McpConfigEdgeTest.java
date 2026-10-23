package com.clara.mcp.registry.config;

import com.clara.mcp.registry.McpTransportProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Validation and merge edge cases for MCP server configuration loading.
 */
public class McpConfigEdgeTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private final Map<String, String> variables = new HashMap<>();
    private final McpServerConfigLoader loader =
            new McpServerConfigLoader(new ObjectMapper(), key -> variables.get(key));

    @Test
    public void serverConfigValidatesTransportShapes() {
        McpServerConfig stdio = McpServerConfig.of("s1", Arrays.asList("node", "server.js"), null, null, null);
        assertEquals(McpTransportProfile.STDIO, stdio.getTransportProfile());
        assertTrue(stdio.getEnv().isEmpty());

        McpServerConfig http = McpServerConfig.of("s2", null, null, " https://host/rpc ", null);
        assertEquals(McpTransportProfile.STREAMABLE_HTTP, http.getTransportProfile());
        assertEquals("https://host/rpc", http.getUrl());

        expectIllegal(new Runnable() { public void run() {
            McpServerConfig.of("s", null, null, null, null); } });
        expectIllegal(new Runnable() { public void run() {
            McpServerConfig.of("s", Arrays.asList("a"), null, "https://h", null); } });
        expectIllegal(new Runnable() { public void run() {
            McpServerConfig.of(" ", Arrays.asList("a"), null, null, null); } });
        expectIllegal(new Runnable() { public void run() {
            new McpServerConfig("s", null, Arrays.asList("a"), null, null, null); } });
        expectIllegal(new Runnable() { public void run() {
            new McpServerConfig("s", McpTransportProfile.STDIO, Collections.<String>emptyList(), null, null, null); } });
        expectIllegal(new Runnable() { public void run() {
            new McpServerConfig("s", McpTransportProfile.STDIO, Arrays.asList("a"), null, "https://h", null); } });
        expectIllegal(new Runnable() { public void run() {
            new McpServerConfig("s", McpTransportProfile.STDIO, Arrays.asList("a"), null, null,
                    Collections.singletonMap("X", "1")); } });
        expectIllegal(new Runnable() { public void run() {
            new McpServerConfig("s", McpTransportProfile.STREAMABLE_HTTP, null, null, null, null); } });
        expectIllegal(new Runnable() { public void run() {
            new McpServerConfig("s", McpTransportProfile.STREAMABLE_HTTP, Arrays.asList("a"), null, "https://h", null); } });
        expectIllegal(new Runnable() { public void run() {
            new McpServerConfig("s", McpTransportProfile.STREAMABLE_HTTP, null,
                    Collections.singletonMap("K", "V"), "https://h", null); } });
        expectIllegal(new Runnable() { public void run() {
            McpServerConfig.of("s", Arrays.asList(" "), null, null, null); } });
        expectIllegal(new Runnable() { public void run() {
            McpServerConfig.of("s", Arrays.asList("a"), Collections.singletonMap(" ", "v"), null, null); } });
        expectIllegal(new Runnable() { public void run() {
            Map<String, String> env = new LinkedHashMap<>();
            env.put("K", null);
            McpServerConfig.of("s", Arrays.asList("a"), env, null, null); } });
    }

    @Test
    public void loaderRejectsMalformedConfigurations() {
        expectIllegal(new Runnable() { public void run() { loader.load("  "); } });
        expectIllegal(new Runnable() { public void run() { loader.load("not json"); } });
        expectIllegal(new Runnable() { public void run() { loader.load("[1,2]"); } });
        expectIllegal(new Runnable() { public void run() { loader.load("{}"); } });
        expectIllegal(new Runnable() { public void run() { loader.load("{\"mcpServers\":[]}"); } });
        expectIllegal(new Runnable() { public void run() { loader.load("{\"mcpServers\":{\"a\":1}}"); } });
        expectIllegal(new Runnable() { public void run() {
            loader.load("{\"mcpServers\":{\"a\":{\"command\":1}}}"); } });
        expectIllegal(new Runnable() { public void run() {
            loader.load("{\"mcpServers\":{\"a\":{\"command\":\"node\",\"args\":\"x\"}}}"); } });
        expectIllegal(new Runnable() { public void run() {
            loader.load("{\"mcpServers\":{\"a\":{\"command\":[\"node\",1]}}}"); } });
        expectIllegal(new Runnable() { public void run() {
            loader.load("{\"mcpServers\":{\"a\":{\"command\":\"node\",\"env\":[]}}}"); } });
        expectIllegal(new Runnable() { public void run() {
            loader.load("{\"mcpServers\":{\"a\":{\"command\":\"node\",\"env\":{\"K\":1}}}}"); } });
        expectIllegal(new Runnable() { public void run() {
            loader.load("{\"mcpServers\":{\"a\":{\"url\":1}}}"); } });
        expectIllegal(new Runnable() { public void run() {
            loader.load("{\"mcpServers\":{\"a\":{\"url\":\"https://h\",\"headers\":[]}}}"); } });
    }

    @Test
    public void loaderSupportsStringCommandAndUndefinedVariablesFail() throws Exception {
        List<McpServerConfig> configs = loader.load("{\"mcpServers\":{\"a\":{\"command\":\"  node  \","
                + "\"args\":[\"--flag\"]}}}");
        assertEquals(2, configs.get(0).getCommand().size());

        expectIllegal(new Runnable() { public void run() {
            loader.load("{\"mcpServers\":{\"a\":{\"command\":\"node\",\"env\":{\"K\":\"${MISSING}\"}}}}"); } });
        expectIllegal(new Runnable() { public void run() {
            loader.load("{\"mcpServers\":{\"a\":{\"url\":\"https://h\",\"headers\":{\"K\":\"${MISSING}\"}}}}"); } });

        variables.put("TOKEN", "t");
        assertEquals("prefix-t-suffix", loader.load("{\"mcpServers\":{\"a\":{\"command\":\"node\","
                + "\"env\":{\"K\":\"prefix-${TOKEN}-suffix\"}}}}").get(0).getEnv().get("K"));

        Path file = folder.newFile("mcp.json").toPath();
        Files.write(file, "{\"mcpServers\":{\"a\":{\"command\":\"node\"}}}".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, loader.loadFile(file).size());
        expectIllegal(new Runnable() { public void run() { loader.loadFile(null); } });
        expectIllegal(new Runnable() { public void run() {
            loader.loadFile(folder.getRoot().toPath().resolve("missing.json")); } });
        expectIllegal(new Runnable() { public void run() {
            new McpServerConfigLoader(null, key -> null); } });
        expectIllegal(new Runnable() { public void run() {
            new McpServerConfigLoader(new ObjectMapper(), null); } });
    }

    @Test
    public void directoryLoaderMergesRuntimeOverClasspath() throws Exception {
        Path runtime = folder.newFolder("runtime").toPath();
        Path nested = Files.createDirectories(runtime.resolve("nested"));

        write(runtime.resolve("a.json"), "{\"mcpServers\":{\"same\":{\"command\":\"first\"},\"only-runtime\":{\"command\":\"r\"}}}");
        write(nested.resolve("b.json"), "{\"mcpServers\":{\"same\":{\"command\":\"second\"}}}");
        Files.write(runtime.resolve("ignored.txt"), "x".getBytes(StandardCharsets.UTF_8));

        McpServerConfigDirectoryLoader directoryLoader = new McpServerConfigDirectoryLoader(loader, runtime);
        List<McpServerConfig> configs = directoryLoader.load();
        assertEquals(2, configs.size());
        assertEquals(runtime, directoryLoader.getRuntimeDirectory());
        assertTrue(!directoryLoader.isIncludeClasspath());

        assertEquals(0, new McpServerConfigDirectoryLoader(loader,
                folder.getRoot().toPath().resolve("absent")).load().size());
        expectIllegal(new Runnable() { public void run() {
            new McpServerConfigDirectoryLoader(null, runtime); } });

        McpServerConfigDirectoryLoader defaults = McpServerConfigDirectoryLoader.defaults();
        assertTrue(defaults.isIncludeClasspath());
        assertTrue(defaults.getRuntimeDirectory().toString().endsWith("mcp"));
    }

    private static void write(Path file, String content) throws Exception {
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void expectIllegal(Runnable action) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage() != null);
        }
    }
}