package com.clara.config;

import com.clara.dto.memory.TopicIsolationConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link TopicIsolationSettings} 的解析测试：默认启用、显式配置生效、非法阈值回退默认值。
 */
public class TopicIsolationSettingsTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void defaultsEnableTopicIsolation() {
        TopicIsolationConfig config = load("context:\n  alwaysPrompts: []\n").toConfig();

        assertTrue(config.isEnabled());
        assertEquals(TopicIsolationConfig.DEFAULT_SHIFT_THRESHOLD, config.getShiftThreshold(), 0.0001d);
        assertEquals(1, config.getCarryOverPreviousTurns());
        assertFalse(config.isInjectArchivedSummary());
        assertEquals(5, config.getExplicitReferenceWindow());
    }

    @Test
    public void explicitConfigurationIsHonored() {
        TopicIsolationConfig config = load("context:\n"
                + "  topicIsolation:\n"
                + "    enabled: false\n"
                + "    shiftThreshold: 0.55\n"
                + "    carryOverPreviousTurns: 2\n"
                + "    injectArchivedSummary: true\n"
                + "    explicitReferenceWindow: 3\n").toConfig();

        assertFalse(config.isEnabled());
        assertEquals(0.55d, config.getShiftThreshold(), 0.0001d);
        assertEquals(2, config.getCarryOverPreviousTurns());
        assertTrue(config.isInjectArchivedSummary());
        assertEquals(3, config.getExplicitReferenceWindow());
    }

    @Test
    public void invalidThresholdFallsBackToDefault() {
        TopicIsolationConfig config = load("context:\n"
                + "  topicIsolation:\n"
                + "    shiftThreshold: 1.5\n").toConfig();

        assertEquals(TopicIsolationConfig.DEFAULT_SHIFT_THRESHOLD, config.getShiftThreshold(), 0.0001d);
    }

    private TopicIsolationSettings load(String yaml) {
        try {
            File file = folder.newFile("clara-" + Math.abs(yaml.hashCode()) + ".yml");
            Files.write(file.toPath(), yaml.replace("\\n", "\n").getBytes(StandardCharsets.UTF_8));
            return TopicIsolationSettings.load(file.toPath());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
