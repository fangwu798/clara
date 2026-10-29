package com.clara.config;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class ToolExecutionSettingsTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void usesDefaultWhenNodeMissing() throws Exception {
        Path file = temporary.newFile("clara.yml").toPath();
        Files.write(file, Arrays.asList("defaultModel: deepseek"), StandardCharsets.UTF_8);
        assertEquals(30_000L, ToolExecutionSettings.load(file).getTimeoutMillis());
    }

    @Test
    public void readsConfiguredTimeout() throws Exception {
        Path file = temporary.newFile("clara.yml").toPath();
        Files.write(file, Arrays.asList("tools:", "  timeoutMillis: 300000"), StandardCharsets.UTF_8);
        assertEquals(300_000L, ToolExecutionSettings.load(file).getTimeoutMillis());
    }

    @Test
    public void clampsTooSmallTimeout() throws Exception {
        Path file = temporary.newFile("clara.yml").toPath();
        Files.write(file, Arrays.asList("tools:", "  timeoutMillis: 10"), StandardCharsets.UTF_8);
        assertEquals(1_000L, ToolExecutionSettings.load(file).getTimeoutMillis());
    }

    @Test
    public void fallsBackForMissingFile() {
        assertEquals(30_000L, ToolExecutionSettings.load(null).getTimeoutMillis());
    }

    @Test
    public void fallsBackForBrokenYaml() throws Exception {
        Path file = temporary.newFile("broken.yml").toPath();
        Files.write(file, Arrays.asList("tools: [unclosed"), StandardCharsets.UTF_8);
        assertEquals(30_000L, ToolExecutionSettings.load(file).getTimeoutMillis());
    }
}
