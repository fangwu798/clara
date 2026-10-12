package com.clara.orchestration.skill;

import com.clara.core.memory.IEmbeddingClient;
import com.clara.core.memory.ISemanticMemoryStore;
import com.clara.core.skill.IPromptProvider;
import com.clara.dto.memory.ContextControlConfig;
import com.clara.dto.memory.MemoryEntry;
import com.clara.dto.memory.MemoryMatch;
import com.clara.dto.memory.SemanticMemoryQuery;
import com.clara.enums.memory.MemorySourceTypeEnum;
import com.clara.dto.tool.ToolContext;
import com.clara.dto.tool.ToolSpecification;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link SkillMatchCoordinator} 单元测试：依赖缺失返回空、按来源检索、按 sourceId 去重、工具名规整。
 */
public class SkillMatchCoordinatorTest {

    private static final String HASH =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    public void matchReturnsEmptyWhenDependenciesMissing() {
        ContextControlConfig control = control(3, 0.5);
        assertTrue(new SkillMatchCoordinator(null, store(empty(), empty()), promptProvider("x"))
                .match("hello", control).getPrompts().isEmpty());
        assertTrue(new SkillMatchCoordinator(text -> new float[] {1f}, null, promptProvider("x"))
                .match("hello", control).getTools().isEmpty());
    }

    @Test
    public void matchReturnsEmptyWhenControlNullOrInputBlank() {
        SkillMatchCoordinator coordinator = new SkillMatchCoordinator(text -> new float[] {1f},
                store(Collections.singletonList(new MemoryMatch(entry(MemorySourceTypeEnum.PROMPT, "p1"), 0.9)),
                        empty()),
                promptProvider("x"));

        assertTrue(coordinator.match("hello", null).getPrompts().isEmpty());
        assertTrue(coordinator.match("  ", control(3, 0.5)).getPrompts().isEmpty());
        assertTrue(coordinator.match(null, control(3, 0.5)).getPrompts().isEmpty());
        assertTrue(coordinator.match("hello", control(0, 0.5)).getPrompts().isEmpty());
    }

    @Test
    public void matchRetrievesPromptsFilteringThresholdAndDeduplicating() {
        List<MemoryMatch> prompts = Arrays.asList(
                new MemoryMatch(entry(MemorySourceTypeEnum.PROMPT, "p1"), 0.9),
                new MemoryMatch(entry(MemorySourceTypeEnum.PROMPT, "p1"), 0.8), // 同源去重
                new MemoryMatch(entry(MemorySourceTypeEnum.PROMPT, "p2"), 0.1)); // 低于阈值

        SkillMatchCoordinator coordinator = new SkillMatchCoordinator(text -> new float[] {1f, 0f},
                store(prompts, empty()), promptProvider("PROMPT-"));

        SkillMatchCoordinator.MatchedSkill matched = coordinator.match("查询天气", control(3, 0.5));

        assertEquals(Collections.singletonList("PROMPT-p1"), matched.getPrompts());
        assertTrue(matched.getTools().isEmpty());
    }

    @Test
    public void matchReturnsAllCatalogToolsWhenCatalogProvided() {
        List<ToolContext> catalogTools = Arrays.asList(
                tool("get_weather"), tool("file_ops"));

        SkillMatchCoordinator coordinator = new SkillMatchCoordinator(text -> new float[] {1f},
                store(empty(), empty()), promptProvider("x"), () -> catalogTools);

        SkillMatchCoordinator.MatchedSkill matched = coordinator.match("hello", control(3, 0.5));

        assertEquals(2, matched.getTools().size());
        assertEquals("get_weather", matched.getTools().get(0).getFunction().getName());
        assertEquals("file_ops", matched.getTools().get(1).getFunction().getName());
    }

    // ---- helpers -----------------------------------------------------------

    private static ContextControlConfig control(int retrievalCount, double threshold) {
        return new ContextControlConfig(-1L, -1, -1L, 80, false, false, retrievalCount, threshold);
    }

    private static MemoryEntry entry(MemorySourceTypeEnum sourceType, String sourceId) {
        return new MemoryEntry("id-" + sourceId, sourceType, sourceId, null, "desc-" + sourceId, HASH,
                new float[] {1f, 0f}, 0L, 0L, null);
    }

    private static List<MemoryMatch> empty() {
        return Collections.emptyList();
    }

    private static IPromptProvider promptProvider(final String prefix) {
        return new IPromptProvider() {
            @Override
            public String prompt(String promptId) {
                return prefix + promptId;
            }

            @Override
            public String summarizePrompt() {
                return "";
            }
        };
    }

    private static ToolContext tool(String name) {
        return new ToolContext(new ToolSpecification(name, "desc-" + name, schema()));
    }

    private static Map<String, Object> schema() {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<String, Object>());
        return schema;
    }

    private static ISemanticMemoryStore store(final List<MemoryMatch> prompts,
            final List<MemoryMatch> tools) {
        return new ISemanticMemoryStore() {
            @Override
            public void upsert(List<MemoryEntry> entries) {
            }

            @Override
            public List<MemoryMatch> search(SemanticMemoryQuery query) {
                return query.getSourceType() == MemorySourceTypeEnum.TOOL_DESCRIPTION ? tools : prompts;
            }

            @Override
            public void deleteById(String entryId) {
            }

            @Override
            public void deleteByConversation(String conversationId) {
            }

            @Override
            public void close() {
            }
        };
    }
}
