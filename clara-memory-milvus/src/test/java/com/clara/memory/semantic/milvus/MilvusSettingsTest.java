package com.clara.memory.semantic.milvus;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link MilvusSettings} 的单元测试：「配了就开、不配保持原样」的开关语义、按项目（当前目录名）隔离的
 * 默认集合名，以及缺省值与非法配置的处理。
 */
public class MilvusSettingsTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void nullPathMeansNotConfigured() {
        assertNull(MilvusSettings.load(null, 128, "demo"));
    }

    @Test
    public void missingFileMeansNotConfigured() {
        Path absent = folder.getRoot().toPath().resolve("absent.yml");
        assertNull(MilvusSettings.load(absent, 128, "demo"));
    }

    @Test
    public void missingMilvusNodeMeansNotConfigured() throws Exception {
        Path config = write("defaultModel: deepseek-completion\n");
        assertNull(MilvusSettings.load(config, 128, "demo"));
    }

    @Test
    public void disabledMeansNotConfigured() throws Exception {
        Path config = write("milvus:\n  enabled: false\n  host: 10.0.0.1\n");
        assertNull(MilvusSettings.load(config, 128, "demo"));
    }

    @Test
    public void defaultsAppliedWhenNodePresent() throws Exception {
        Path config = write("milvus:\n  enabled: true\n");
        MilvusSettings settings = MilvusSettings.load(config, 128, "demo");
        assertNotNull(settings);
        assertEquals(MilvusSettings.DEFAULT_HOST, settings.getHost());
        assertEquals(MilvusSettings.DEFAULT_PORT, settings.getPort());
        assertEquals("demo_conversations", settings.getCollection());
        assertEquals(128, settings.getDimension());
        assertNull(settings.getUsername());
        assertNull(settings.getPassword());
    }

    @Test
    public void differentProjectsUseDifferentDefaultCollections() {
        assertNotEquals(MilvusSettings.defaultCollection("project-a"),
                MilvusSettings.defaultCollection("project-b"));
        assertEquals("project_a_conversations", MilvusSettings.defaultCollection("project-a"));
    }

    @Test
    public void defaultCollectionSanitizedForMilvusNaming() {
        assertEquals("my_project_1_x_conversations", MilvusSettings.defaultCollection("My Project-1.x"));
        assertEquals("clara_conversations", MilvusSettings.defaultCollection(null));
        assertEquals("clara_conversations", MilvusSettings.defaultCollection("   "));
        assertEquals("p1abc_conversations", MilvusSettings.defaultCollection("1abc"));
    }

    @Test
    public void explicitCollectionOverridesProjectDefault() throws Exception {
        Path config = write("milvus:\n  collection: shared_conversations\n");
        MilvusSettings settings = MilvusSettings.load(config, 128, "demo");
        assertNotNull(settings);
        assertEquals("shared_conversations", settings.getCollection());
    }

    @Test
    public void explicitValuesAreRead() throws Exception {
        Path config = write("milvus:\n"
                + "  host: 10.1.2.3\n"
                + "  port: 19531\n"
                + "  collection: my_conversations\n"
                + "  dimension: 768\n"
                + "  username: root\n"
                + "  password: Milvus\n");
        MilvusSettings settings = MilvusSettings.load(config, 128, "demo");
        assertNotNull(settings);
        assertEquals("10.1.2.3", settings.getHost());
        assertEquals(19531, settings.getPort());
        assertEquals("my_conversations", settings.getCollection());
        assertEquals(768, settings.getDimension());
        assertEquals("root", settings.getUsername());
        assertEquals("Milvus", settings.getPassword());
    }

    @Test(expected = IllegalArgumentException.class)
    public void nonPositivePortRejected() throws Exception {
        MilvusSettings.load(write("milvus:\n  port: 0\n"), 128, "demo");
    }

    @Test(expected = IllegalArgumentException.class)
    public void nonNumericPortRejected() throws Exception {
        MilvusSettings.load(write("milvus:\n  port: abc\n"), 128, "demo");
    }

    @Test(expected = IllegalArgumentException.class)
    public void nonPositiveDimensionRejected() throws Exception {
        MilvusSettings.load(write("milvus:\n  dimension: -5\n"), 128, "demo");
    }

    @Test(expected = IllegalArgumentException.class)
    public void missingDimensionWithInvalidDefaultRejected() throws Exception {
        MilvusSettings.load(write("milvus:\n  host: 127.0.0.1\n"), 0, "demo");
    }

    @Test
    public void defaultsToVectorMode() throws Exception {
        MilvusSettings settings = MilvusSettings.load(write("milvus:\n  enabled: true\n"), 128, "demo");
        assertNotNull(settings);
        assertEquals(MilvusSettings.MODE_VECTOR, settings.getMode());
        assertFalse(settings.isBm25Mode());
        assertEquals(MilvusSettings.DEFAULT_ANALYZER, settings.getAnalyzer());
        assertFalse(settings.isRecreateOnSchemaMismatch());
    }

    @Test
    public void bm25ModeAndFlagsAreRead() throws Exception {
        MilvusSettings settings = MilvusSettings.load(write("milvus:\n"
                + "  mode: bm25\n"
                + "  analyzer: chinese\n"
                + "  recreateOnSchemaMismatch: true\n"), 128, "demo");
        assertNotNull(settings);
        assertEquals(MilvusSettings.MODE_BM25, settings.getMode());
        assertTrue(settings.isBm25Mode());
        assertEquals("chinese", settings.getAnalyzer());
        assertTrue(settings.isRecreateOnSchemaMismatch());
    }

    @Test
    public void bm25ModeFallsBackToChineseAnalyzer() throws Exception {
        MilvusSettings settings = MilvusSettings.load(write("milvus:\n  mode: bm25\n"), 128, "demo");
        assertNotNull(settings);
        assertEquals(MilvusSettings.DEFAULT_ANALYZER, settings.getAnalyzer());
    }

    @Test
    public void modeIsCaseInsensitiveAndTrimmed() throws Exception {
        MilvusSettings settings = MilvusSettings.load(write("milvus:\n  mode: BM25\n"), 128, "demo");
        assertNotNull(settings);
        assertTrue(settings.isBm25Mode());
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownModeRejected() throws Exception {
        MilvusSettings.load(write("milvus:\n  mode: hybrid\n"), 128, "demo");
    }

    @Test(expected = IllegalArgumentException.class)
    public void nonBooleanRecreateFlagRejected() throws Exception {
        MilvusSettings.load(write("milvus:\n  recreateOnSchemaMismatch: sometimes\n"), 128, "demo");
    }

    private Path write(String yaml) throws Exception {
        File file = folder.newFile("clara.yml");
        Files.write(file.toPath(), yaml.getBytes(StandardCharsets.UTF_8));
        return file.toPath();
    }
}
