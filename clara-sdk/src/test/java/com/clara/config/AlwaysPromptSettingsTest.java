package com.clara.config;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link AlwaysPromptSettings} 的解析测试：默认常驻图谱使用约定、显式清单照配置生效、写 [] 表示不常驻。
 *
 * <p>为什么盯这几个：常驻提示词是「读代码先查图谱」这类固定约定唯一的注入通道（按输入相似度检索指望不上），
 * 默认值失效或 [] 被当成「缺省」都会让约定整体消失，而这类问题在对话里看不出来、只能靠配置解析测试兜住。
 */
public class AlwaysPromptSettingsTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void defaultsKeepGraphGuideAlwaysOn() {
        assertEquals(Collections.singletonList(AlwaysPromptSettings.CODE_GRAPH_GUIDE),
                load("milvus:\n  enabled: false\n").getIds());
    }

    @Test
    public void explicitListIsHonored() {
        assertEquals(Arrays.asList("a", "b"), load("context:\n  alwaysPrompts: [a, b]\n").getIds());
    }

    @Test
    public void emptyListMeansNoAlwaysOnPrompt() {
        assertTrue(load("context:\n  alwaysPrompts: []\n").getIds().isEmpty());
    }

    @Test
    public void nonListValueFallsBackToDefault() {
        assertEquals(Collections.singletonList(AlwaysPromptSettings.CODE_GRAPH_GUIDE),
                load("context:\n  alwaysPrompts: code_graph_guide\n").getIds());
    }

    private AlwaysPromptSettings load(String yaml) {
        try {
            File file = folder.newFile("clara-" + Math.abs(yaml.hashCode()) + ".yml");
            Files.write(file.toPath(), yaml.replace("\\n", "\n").getBytes(StandardCharsets.UTF_8));
            return AlwaysPromptSettings.load(file.toPath());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
