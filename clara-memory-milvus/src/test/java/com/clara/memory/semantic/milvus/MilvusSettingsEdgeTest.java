package com.clara.memory.semantic.milvus;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link MilvusSettings} 的边界用例：集合名规整与非法值。
 *
 * <p>为什么单独测集合名：集合名直接决定「哪些项目共用一份记忆库」。规整规则漏一条
 * （例如未折叠连续下划线、未截断超长目录名）会静默生成一个 Milvus 拒绝的名字，
 * 表现是每次启动都连接失败，却看不出是名字的问题。
 */
public class MilvusSettingsEdgeTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void blankCollectionFallsBackToProjectDefault() throws Exception {
        // 空白集合名按「未声明」处理：退回「项目名_conversations」，避免把空串发给 Milvus
        Path config = write("milvus:\n  collection: \"   \"\n");
        assertEquals("demo_conversations", MilvusSettings.load(config, 3, "demo").getCollection());
    }

    @Test
    public void projectNameIsNormalized() {
        assertEquals("a_b_conversations", MilvusSettings.defaultCollection("__a__b__"));
        assertEquals("abc_conversations", MilvusSettings.defaultCollection("___abc___"));
        assertEquals("a_b_conversations", MilvusSettings.defaultCollection("a - b"));
    }

    @Test
    public void overlongProjectNameIsTruncated() {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            name.append('x');
        }
        String collection = MilvusSettings.defaultCollection(name.toString());
        assertEquals("超长项目名应截断到 200 个字符再加后缀",
                200 + MilvusSettings.COLLECTION_SUFFIX.length(), collection.length());
        assertTrue(collection.endsWith(MilvusSettings.COLLECTION_SUFFIX));
    }

    @Test
    public void blankTextFallsBackToDefault() throws Exception {
        Path config = write("milvus:\n  host: \"   \"\n  username: \"\"\n");
        MilvusSettings settings = MilvusSettings.load(config, 3, "demo");
        assertEquals(MilvusSettings.DEFAULT_HOST, settings.getHost());
        assertEquals(null, settings.getUsername());
    }

    private Path write(String yaml) throws Exception {
        Path file = folder.newFile("clara.yml").toPath();
        Files.write(file, yaml.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}