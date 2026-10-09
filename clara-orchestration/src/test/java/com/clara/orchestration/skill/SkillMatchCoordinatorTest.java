package com.clara.orchestration.skill;

import com.clara.core.memory.IEmbeddingClient;
import com.clara.core.memory.ISemanticMemoryStore;
import com.clara.core.skill.IPromptProvider;
import com.clara.dto.memory.ContextControlConfig;
import com.clara.dto.memory.MemoryEntry;
import com.clara.dto.memory.MemoryMatch;
import com.clara.dto.memory.SemanticMemoryQuery;
import com.clara.enums.memory.MemorySourceTypeEnum;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
    public void matchRetrievesPromptsAndToolsFilteringThresholdAndDeduplicating() {
        List<MemoryMatch> prompts = Arrays.asList(
                new MemoryMatch(entry(MemorySourceTypeEnum.PROMPT, "p1"), 0.9),
                new MemoryMatch(entry(MemorySourceTypeEnum.PROMPT, "p1"), 0.8), // 同源去重
                new MemoryMatch(entry(MemorySourceTypeEnum.PROMPT, "p2"), 0.1)); // 低于阈值
        List<MemoryMatch> tools = Collections.singletonList(
                new MemoryMatch(entry(MemorySourceTypeEnum.TOOL_DESCRIPTION, "get_weather"), 0.7));

        SkillMatchCoordinator coordinator = new SkillMatchCoordinator(text -> new float[] {1f, 0f},
                store(prompts, tools), promptProvider("PROMPT-"));

        SkillMatchCoordinator.MatchedSkill matched = coordinator.match("查询天气", control(3, 0.5));

        assertEquals(Collections.singletonList("PROMPT-p1"), matched.getPrompts());
        assertEquals(1, matched.getTools().size());
        assertEquals("get_weather", matched.getTools().get(0).getFunction().getName());
        assertEquals("desc-get_weather", matched.getTools().get(0).getFunction().getDescription());
    }

    @Test
    public void matchNormalizesToolNamesReplacingIllegalCharsAndTruncating() {
        StringBuilder longId = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            longId.append('a');
        }
        String dirtyId = "my tool.name!";

        List<MemoryMatch> tools = Arrays.asList(
                new MemoryMatch(entry(MemorySourceTypeEnum.TOOL_DESCRIPTION, dirtyId), 0.9),
                new MemoryMatch(entry(MemorySourceTypeEnum.TOOL_DESCRIPTION, longId.toString()), 0.9));

        SkillMatchCoordinator coordinator = new SkillMatchCoordinator(text -> new float[] {1f},
                store(empty(), tools), promptProvider("x"));

        SkillMatchCoordinator.MatchedSkill matched = coordinator.match("hello", control(3, 0.5));

        List<com.clara.tool.ToolContext> result = matched.getTools();
        assertEquals(2, result.size());
        assertEquals("my_tool_name_", result.get(0).getFunction().getName());
        assertEquals(128, result.get(1).getFunction().getName().length());
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
