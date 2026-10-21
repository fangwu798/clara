package com.clara.dto.memory;

import com.clara.enums.memory.MemorySourceTypeEnum;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 记忆相关 DTO 的构建、校验与防御性复制规则。
 */
public class MemoryDtoTest {

    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    /**
     * 合法记忆条目：字段可读、向量与元数据做防御性复制、过期判断按毫秒边界。
     */
    @Test
    public void buildsValidMemoryEntry() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("k", "v");
        float[] vector = {1.0f, 2.0f};
        MemoryEntry entry = MemoryEntry.builder("id-1", MemorySourceTypeEnum.CONVERSATION, "conv-1",
                "hello", HASH, vector)
                .conversationId("conv-1")
                .createdAtEpochMillis(1000L)
                .expiresAtEpochMillis(2000L)
                .metadata(metadata)
                .build();

        assertEquals("id-1", entry.getId());
        assertEquals(MemorySourceTypeEnum.CONVERSATION, entry.getSourceType());
        assertEquals("conv-1", entry.getSourceId());
        assertEquals("conv-1", entry.getConversationId());
        assertEquals("hello", entry.getContent());
        assertEquals(HASH, entry.getContentHash());
        assertEquals(2, entry.getDimension());
        assertEquals(1000L, entry.getCreatedAtEpochMillis());
        assertEquals(2000L, entry.getExpiresAtEpochMillis());
        assertFalse(entry.isExpired(999L));
        assertTrue(entry.isExpired(2000L));

        vector[0] = 99.0f;
        assertArrayEquals(new float[]{1.0f, 2.0f}, entry.getVector(), 0.0f);
        float[] copy = entry.getVector();
        copy[1] = 99.0f;
        assertArrayEquals(new float[]{1.0f, 2.0f}, entry.getVector(), 0.0f);

        metadata.put("k", "changed");
        assertEquals("v", entry.getMetadata().get("k"));
        assertThrows(UnsupportedOperationException.class, () -> entry.getMetadata().put("x", "y"));

        assertTrue(entry.equals(entry));
        assertFalse(entry.equals("other"));
        assertEquals(entry.hashCode(), entry.hashCode());
        assertNotNull(entry.toString());
    }

    /**
     * 必填字段非法时拒绝构建。
     */
    @Test
    public void rejectsInvalidMemoryEntry() {
        assertThrows(IllegalArgumentException.class, () -> MemoryEntry.builder(" ", MemorySourceTypeEnum.PROMPT,
                "s", "c", HASH, new float[]{1f}).build());
        assertThrows(IllegalArgumentException.class, () -> MemoryEntry.builder("id", null,
                "s", "c", HASH, new float[]{1f}).build());
        assertThrows(IllegalArgumentException.class, () -> MemoryEntry.builder("id", MemorySourceTypeEnum.PROMPT,
                "", "c", HASH, new float[]{1f}).build());
        assertThrows(IllegalArgumentException.class, () -> MemoryEntry.builder("id", MemorySourceTypeEnum.PROMPT,
                "s", null, HASH, new float[]{1f}).build());
        assertThrows(IllegalArgumentException.class, () -> MemoryEntry.builder("id", MemorySourceTypeEnum.PROMPT,
                "s", "c", "short", new float[]{1f}).build());
        assertThrows(IllegalArgumentException.class, () -> MemoryEntry.builder("id", MemorySourceTypeEnum.PROMPT,
                "s", "c", HASH, new float[0]).build());
        assertThrows(IllegalArgumentException.class, () -> MemoryEntry.builder("id", MemorySourceTypeEnum.PROMPT,
                "s", "c", HASH, new float[]{Float.NaN}).build());
        assertThrows(IllegalArgumentException.class, () -> MemoryEntry.builder("id", MemorySourceTypeEnum.PROMPT,
                "s", "c", HASH, new float[]{Float.POSITIVE_INFINITY}).build());
        assertThrows(IllegalArgumentException.class, () -> MemoryEntry.builder("id", MemorySourceTypeEnum.PROMPT,
                "s", "c", HASH, new float[]{0f, 0f}).build());
        assertThrows(IllegalArgumentException.class, () -> MemoryEntry.builder("id", MemorySourceTypeEnum.PROMPT,
                "s", "c", HASH, new float[]{1f}).createdAtEpochMillis(-1L).build());
        assertThrows(IllegalArgumentException.class, () -> MemoryEntry.builder("id", MemorySourceTypeEnum.PROMPT,
                "s", "c", HASH, new float[]{1f}).expiresAtEpochMillis(-1L).build());
        assertThrows(IllegalArgumentException.class, () -> MemoryEntry.builder("id", MemorySourceTypeEnum.PROMPT,
                "s", "c", HASH, new float[]{1f}).createdAtEpochMillis(10L).expiresAtEpochMillis(10L).build());
        assertThrows(IllegalArgumentException.class, () -> MemoryEntry.builder("id", MemorySourceTypeEnum.PROMPT,
                "s", "c", HASH, new float[]{1f}).metadata(Collections.singletonMap(" ", "v")).build());
        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("k", null);
        assertThrows(IllegalArgumentException.class, () -> MemoryEntry.builder("id", MemorySourceTypeEnum.PROMPT,
                "s", "c", HASH, new float[]{1f}).metadata(nullValue).build());
    }

    /**
     * 默认配置与构建器：各字段按设置生效。
     */
    @Test
    public void buildsContextControlConfig() {
        ContextControlConfig defaults = ContextControlConfig.defaults();
        assertEquals(-1L, defaults.getContextWindowTokens());
        assertEquals(-1, defaults.getWindowMaxMessages());
        assertEquals(-1L, defaults.getWindowMaxSizeBytes());
        assertEquals(80, defaults.getWindowMaxTokensPercent());
        assertEquals(3, defaults.getRetrievalCount());
        assertEquals(0.7, defaults.getSimilarityThreshold(), 0.0);
        assertEquals(ContextControlConfig.DEFAULT_TOOL_OUTPUT_MAX_CHARS, defaults.getToolOutputMaxChars());
        assertEquals(ContextControlConfig.DEFAULT_TOOL_OUTPUT_MAX_CHARS
                * ContextControlConfig.TOOL_OUTPUT_BUDGET_MULTIPLIER, defaults.getToolOutputBudgetChars());
        assertFalse(defaults.isCompressEnabled());
        assertFalse(defaults.isVectorizeEnabled());

        ContextControlConfig config = ContextControlConfig.builder()
                .contextWindowTokens(1000L)
                .windowMaxMessages(10)
                .windowMaxSizeBytes(2048L)
                .windowMaxTokensPercent(50)
                .compressEnabled(true)
                .vectorizeEnabled(true)
                .retrievalCount(5)
                .similarityThreshold(0.9)
                .toolOutputMaxChars(100)
                .build();
        assertEquals(1000L, config.getContextWindowTokens());
        assertEquals(10, config.getWindowMaxMessages());
        assertEquals(2048L, config.getWindowMaxSizeBytes());
        assertEquals(50, config.getWindowMaxTokensPercent());
        assertTrue(config.isCompressEnabled());
        assertTrue(config.isVectorizeEnabled());
        assertEquals(5, config.getRetrievalCount());
        assertEquals(0.9, config.getSimilarityThreshold(), 0.0);
        assertEquals(100, config.getToolOutputMaxChars());
        assertEquals(100 * ContextControlConfig.TOOL_OUTPUT_BUDGET_MULTIPLIER,
                config.getToolOutputBudgetChars());
        assertEquals(-1, ContextControlConfig.builder()
                .contextWindowTokens(-1).windowMaxMessages(-1).toolOutputMaxChars(-1)
                .build().getToolOutputBudgetChars());
    }

    /**
     * 上下文配置的每个数值字段都有范围校验。
     */
    @Test
    public void rejectsInvalidContextControlConfig() {
        assertThrows(IllegalArgumentException.class, () -> ContextControlConfig.builder().contextWindowTokens(0).build());
        assertThrows(IllegalArgumentException.class, () -> ContextControlConfig.builder().contextWindowTokens(-2).build());
        assertThrows(IllegalArgumentException.class, () -> ContextControlConfig.builder().windowMaxMessages(0).build());
        assertThrows(IllegalArgumentException.class, () -> ContextControlConfig.builder().windowMaxSizeBytes(-2).build());
        assertThrows(IllegalArgumentException.class, () -> ContextControlConfig.builder().windowMaxTokensPercent(-1).build());
        assertThrows(IllegalArgumentException.class, () -> ContextControlConfig.builder().windowMaxTokensPercent(101).build());
        assertThrows(IllegalArgumentException.class, () -> ContextControlConfig.builder().retrievalCount(-1).build());
        assertThrows(IllegalArgumentException.class, () -> ContextControlConfig.builder().similarityThreshold(Double.NaN).build());
        assertThrows(IllegalArgumentException.class, () -> ContextControlConfig.builder().similarityThreshold(-0.1).build());
        assertThrows(IllegalArgumentException.class, () -> ContextControlConfig.builder().similarityThreshold(1.1).build());
        assertThrows(IllegalArgumentException.class, () -> ContextControlConfig.builder().toolOutputMaxChars(0).build());
    }

    /**
     * 语义查询：过滤条件复制为只读、空白查询文本归 null、向量做防御性复制。
     */
    @Test
    public void buildsSemanticMemoryQuery() {
        Map<String, String> filters = new HashMap<>();
        filters.put("source", "prompt");
        float[] vector = {1f, 0f};
        SemanticMemoryQuery query = SemanticMemoryQuery.builder(vector, 5, 0.5, 100L)
                .sourceType(MemorySourceTypeEnum.PROMPT)
                .conversationId("conv")
                .filters(filters)
                .queryText("  ")
                .build();

        assertEquals(MemorySourceTypeEnum.PROMPT, query.getSourceType());
        assertEquals("conv", query.getConversationId());
        assertEquals(5, query.getTopK());
        assertEquals(0.5, query.getMinScore(), 0.0);
        assertEquals(100L, query.getNowEpochMillis());
        assertEquals("prompt", query.getFilters().get("source"));
        assertNull(query.getQueryText());

        vector[0] = 9f;
        assertArrayEquals(new float[]{1f, 0f}, query.getVector(), 0.0f);
        filters.put("source", "changed");
        assertEquals("prompt", query.getFilters().get("source"));
        assertThrows(UnsupportedOperationException.class, () -> query.getFilters().put("x", "y"));
    }

    /**
     * 语义查询参数越界时拒绝构建。
     */
    @Test
    public void rejectsInvalidSemanticMemoryQuery() {
        assertThrows(IllegalArgumentException.class, () -> SemanticMemoryQuery.builder(null, 1, 0.0, 0L).build());
        assertThrows(IllegalArgumentException.class, () -> SemanticMemoryQuery.builder(new float[]{1f}, 0, 0.0, 0L).build());
        assertThrows(IllegalArgumentException.class, () -> SemanticMemoryQuery.builder(new float[]{1f}, 1, 1.1, 0L).build());
        assertThrows(IllegalArgumentException.class, () -> SemanticMemoryQuery.builder(new float[]{1f}, 1, -1.1, 0L).build());
        assertThrows(IllegalArgumentException.class, () -> SemanticMemoryQuery.builder(new float[]{1f}, 1, Double.NaN, 0L).build());
        assertThrows(IllegalArgumentException.class, () -> SemanticMemoryQuery.builder(new float[]{1f}, 1, 0.0, -1L).build());
        assertThrows(IllegalArgumentException.class, () -> SemanticMemoryQuery.builder(new float[]{0f}, 1, 0.0, 0L).build());
        assertThrows(IllegalArgumentException.class, () -> SemanticMemoryQuery.builder(new float[]{1f}, 1, 0.0, 0L)
                .filters(Collections.singletonMap("k", null)).build());
    }

    /**
     * 窗口策略默认值与边界校验。
     */
    @Test
    public void validatesWindowPolicy() {
        WindowPolicy policy = WindowPolicy.defaults();
        assertEquals(-1L, policy.getContextWindowTokens());
        assertEquals(-1, policy.getMaxMessages());
        assertEquals(-1L, policy.getMaxSizeBytes());
        assertEquals(80, policy.getMaxTokensPercent());

        WindowPolicy custom = new WindowPolicy(100L, 10, 1024L, 50);
        assertEquals(100L, custom.getContextWindowTokens());
        assertEquals(10, custom.getMaxMessages());
        assertEquals(1024L, custom.getMaxSizeBytes());
        assertEquals(50, custom.getMaxTokensPercent());
        assertEquals(custom, new WindowPolicy(100L, 10, 1024L, 50));
        assertFalse(custom.equals(null));

        assertThrows(IllegalArgumentException.class, () -> new WindowPolicy(0L, 1, 1L, 1));
        assertThrows(IllegalArgumentException.class, () -> new WindowPolicy(-2L, 1, 1L, 1));
        assertThrows(IllegalArgumentException.class, () -> new WindowPolicy(1L, 0, 1L, 1));
        assertThrows(IllegalArgumentException.class, () -> new WindowPolicy(1L, -2, 1L, 1));
        assertThrows(IllegalArgumentException.class, () -> new WindowPolicy(1L, 1, -2L, 1));
        assertThrows(IllegalArgumentException.class, () -> new WindowPolicy(1L, 1, 1L, -1));
        assertThrows(IllegalArgumentException.class, () -> new WindowPolicy(1L, 1, 1L, 101));
    }

    /**
     * 记忆匹配对象携带条目与得分。
     */
    @Test
    public void exposesMemoryMatch() {
        MemoryEntry entry = MemoryEntry.builder("id", MemorySourceTypeEnum.PROMPT, "s", "c", HASH, new float[]{1f}).build();
        MemoryMatch match = new MemoryMatch(entry, 0.8);
        assertEquals(entry, match.getEntry());
        assertEquals(0.8, match.getScore(), 0.0);
    }
}
