package com.clara.orchestration.skill;

import com.clara.core.memory.IContextTraceSink;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link SkillMatchCoordinator} 单元测试：依赖缺失返回空、按来源过滤与 sourceId 去重、工具声明全量下发。
 *
 * <p>另外盯住「上报给观测口的内容」：命中条数、阈值与候选数、每条命中的分数与 id，
 * 这些正是控制台观察本轮提示词检索时要看的东西，且不影响对话结果。
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

        MatchedSkill matched = coordinator.match("查询引用", control(3, 0.5));

        assertEquals(Collections.singletonList("PROMPT-p1"), matched.getPrompts());
        assertTrue(matched.getTools().isEmpty());
    }

    @Test
    public void matchReturnsAllCatalogToolsWhenCatalogProvided() {
        List<ToolContext> catalogTools = Arrays.asList(
                tool("get_weather"), tool("file_ops"));

        SkillMatchCoordinator coordinator = new SkillMatchCoordinator(text -> new float[] {1f},
                store(empty(), empty()), promptProvider("x"), () -> catalogTools);

        MatchedSkill matched = coordinator.match("hello", control(3, 0.5));

        assertEquals(2, matched.getTools().size());
        assertEquals("get_weather", matched.getTools().get(0).getFunction().getName());
        assertEquals("file_ops", matched.getTools().get(1).getFunction().getName());
    }

    @Test
    public void matchReportsHitsWithThresholdAndScores() {
        RecordingTrace trace = new RecordingTrace();
        SkillMatchCoordinator coordinator = new SkillMatchCoordinator(text -> new float[] {1f, 0f},
                store(Arrays.asList(
                        new MemoryMatch(entry(MemorySourceTypeEnum.PROMPT, "p1"), 0.9),
                        new MemoryMatch(entry(MemorySourceTypeEnum.PROMPT, "p1"), 0.8),
                        new MemoryMatch(entry(MemorySourceTypeEnum.PROMPT, "p2"), 0.1)),
                        empty()),
                promptProvider("PROMPT-"), null, trace);

        coordinator.match("查询引用", control(3, 0.5));

        assertEquals(Collections.singletonList("prompt_match"), trace.sources);
        assertEquals(Collections.singletonList(1), trace.hits);
        // 明细先报「召回几条 / 过阈值几条」：未命中时才能分清是库里没数据还是分数不够
        List<String> details = trace.details.get(0);
        assertEquals(2, details.size());
        assertTrue(details.get(0).contains("召回 3 条"));
        assertTrue(details.get(0).contains("过阈值 2 条"));
        assertTrue(details.get(0).contains("阈值 0.50"));
        assertTrue(details.get(0).contains("最高分 0.900"));
        assertTrue(details.get(1).contains("score=0.900"));
        assertTrue(details.get(1).contains("p1"));
    }

    @Test
    public void matchReportsMissAndDisabledRetrieval() {
        RecordingTrace missTrace = new RecordingTrace();
        SkillMatchCoordinator missing = new SkillMatchCoordinator(text -> new float[] {1f},
                store(Collections.singletonList(
                        new MemoryMatch(entry(MemorySourceTypeEnum.PROMPT, "p1"), 0.1)), empty()),
                promptProvider("x"), null, missTrace);

        assertTrue(missing.match("hello", control(3, 0.5)).getPrompts().isEmpty());
        assertEquals(Collections.singletonList(0), missTrace.hits);

        RecordingTrace disabledTrace = new RecordingTrace();
        SkillMatchCoordinator disabled = new SkillMatchCoordinator(text -> new float[] {1f},
                store(empty(), empty()), promptProvider("x"), null, disabledTrace);

        assertTrue(disabled.match("hello", control(0, 0.5)).getPrompts().isEmpty());
        assertEquals(Collections.singletonList(0), disabledTrace.hits);
        assertTrue(disabledTrace.details.get(0).get(0).contains("未开启提示词检索"));
    }

    // ---- helpers -----------------------------------------------------------

    private static ContextControlConfig control(int retrievalCount, double threshold) {
        return ContextControlConfig.builder()
                .contextWindowTokens(-1L).windowMaxMessages(-1).windowMaxSizeBytes(-1L)
                .windowMaxTokensPercent(80).retrievalCount(retrievalCount)
                .similarityThreshold(threshold).build();
    }

    private static MemoryEntry entry(MemorySourceTypeEnum sourceType, String sourceId) {
        return MemoryEntry.builder("id-" + sourceId, sourceType, sourceId, "desc-" + sourceId, HASH,
                new float[] {1f, 0f}).build();
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
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>());
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

    /** 记录上报内容的观测口：看「上报了哪些点、分别是什么」。 */
    private static final class RecordingTrace implements IContextTraceSink {

        private final List<String> sources = new ArrayList<>();
        private final List<Integer> hits = new ArrayList<>();
        private final List<List<String>> details = new ArrayList<>();

        @Override
        public void onTrace(String source, int hitCount, long elapsedMillis, List<String> rendered) {
            sources.add(source);
            hits.add(hitCount);
            details.add(new ArrayList<>(rendered));
        }
    }
}
