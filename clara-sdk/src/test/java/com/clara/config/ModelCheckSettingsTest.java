package com.clara.config;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class ModelCheckSettingsTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void usesDefaultsWhenNodeMissing() throws Exception {
        Path file = temporary.newFile("clara.yml").toPath();
        Files.write(file, Arrays.asList("defaultModel: deepseek"), StandardCharsets.UTF_8);
        ModelCheckSettings settings = ModelCheckSettings.load(file);
        assertEquals(60_000L, settings.getTimeoutMillis());
        assertEquals(1, settings.getRetries());
    }

    @Test
    public void readsConfiguredValues() throws Exception {
        Path file = temporary.newFile("clara.yml").toPath();
        Files.write(file, Arrays.asList("modelCheck:", "  timeoutMillis: 90000", "  retries: 2"),
                StandardCharsets.UTF_8);
        ModelCheckSettings settings = ModelCheckSettings.load(file);
        assertEquals(90_000L, settings.getTimeoutMillis());
        assertEquals(2, settings.getRetries());
    }

    @Test
    public void clampsIllegalValues() throws Exception {
        Path file = temporary.newFile("clara.yml").toPath();
        Files.write(file, Arrays.asList("modelCheck:", "  timeoutMillis: 100", "  retries: 99"),
                StandardCharsets.UTF_8);
        ModelCheckSettings settings = ModelCheckSettings.load(file);
        assertEquals(5_000L, settings.getTimeoutMillis());
        assertEquals(3, settings.getRetries());
    }

    @Test
    public void fallsBackForMissingFile() {
        ModelCheckSettings settings = ModelCheckSettings.load(null);
        assertEquals(60_000L, settings.getTimeoutMillis());
        assertEquals(1, settings.getRetries());
    }
}