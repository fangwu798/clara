package com.clara.config;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link CodeGraphSettings} 的解析测试：重点是启动挂载开关（autoMount）与每轮预取（supplement）的默认值，
 * 以及它们受总开关约束。
 *
 * <p>为什么盯这两个：autoMount 默认开着才符合「换到别的工作目录也能用图谱」的预期；而它若不跟着 enabled
 * 一起关，关掉图谱的机器上启动时也照样会去碰容器。
 */
public class CodeGraphSettingsTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void defaultsEnableMountButNotSupplement() throws Exception {
        CodeGraphSettings settings = load("codeGraph:\n  enabled: true\n");
        assertTrue(settings.isEnabled());
        assertTrue(settings.isAutoMountEnabled());
        // 每轮预取默认关：实测大仓库单次检索 20 秒+，压在每轮模型之前会把首字响应拖垮
        assertFalse(settings.isSupplementEnabled());
        assertFalse(settings.isAutoImportEnabled());
    }

    @Test
    public void supplementCanBeTurnedBackOn() throws Exception {
        assertTrue(load("codeGraph:\n  enabled: true\n  supplement: true\n").isSupplementEnabled());
    }

    @Test
    public void missingNodeFallsBackToDefaults() throws Exception {
        assertTrue(load("milvus:\n  enabled: false\n").isAutoMountEnabled());
    }

    @Test
    public void autoMountFollowsTheMasterSwitch() throws Exception {
        assertFalse(load("codeGraph:\n  enabled: false\n  autoMount: true\n").isAutoMountEnabled());
        assertFalse(load("codeGraph:\n  enabled: true\n  autoMount: false\n").isAutoMountEnabled());
    }

    @Test
    public void nonBooleanValueFallsBackToDefault() throws Exception {
        assertTrue(load("codeGraph:\n  enabled: true\n  autoMount: \"yes\"\n").isAutoMountEnabled());
    }

    private CodeGraphSettings load(String yaml) throws Exception {
        File file = folder.newFile("clara-" + Math.abs(yaml.hashCode()) + ".yml");
        Files.write(file.toPath(), yaml.getBytes(StandardCharsets.UTF_8));
        return CodeGraphSettings.load(file.toPath());
    }
}
