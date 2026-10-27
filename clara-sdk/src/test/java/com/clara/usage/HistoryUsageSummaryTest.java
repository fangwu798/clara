package com.clara.usage;

import com.clara.core.message.IContent;
import com.clara.kernel.message.delta.AssistantContent;
import com.clara.kernel.message.delta.TokenUsage;
import com.clara.kernel.message.delta.UserContent;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 历史 token 汇总：只累加落盘的真实用量、旧消息只计数、模型分组与展示文案。
 */
public class HistoryUsageSummaryTest {

    /**
     * 有真实用量时直接累加，并按「平台/模型」分组，不再做估算。
     */
    @Test
    public void sumsRecordedUsageAndGroupsByModel() {
        List<IContent> history = Arrays.asList(
                new UserContent("问"),
                new AssistantContent("答一", new TokenUsage(100, 10, 110, null), "DEEPSEEK/a"),
                new UserContent("再问"),
                new AssistantContent("答二", new TokenUsage(200, 20, null, null), "DEEPSEEK/a"));

        HistoryUsageSummary summary = HistoryUsageSummary.of(history);

        assertEquals(2L, summary.getRecorded().getCalls());
        assertEquals(300L, summary.getRecorded().getInputTokens());
        assertEquals(30L, summary.getRecorded().getOutputTokens());
        assertEquals(330L, summary.getRecorded().getTotalTokens());
        assertEquals(2L, summary.getByModel().get("DEEPSEEK/a").getCalls());
        String line = summary.summaryLine();
        assertTrue(line.contains("历史记录：4 条"));
        assertTrue(line.contains("已记录 2 条回答用量"));
        assertTrue(line.contains("输入 300"));
        assertTrue(line.contains("输出 30"));
        assertTrue(line.contains("合计 330 tokens"));
        assertFalse(line.contains("无 token 记录"));
    }

    /**
     * 升级前写入的助手消息没有用量：只计数提示，不参与统计，也不做估算。
     */
    @Test
    public void countsUnrecordedLegacyMessagesWithoutEstimating() {
        List<IContent> history = Arrays.asList(
                new UserContent("旧问"),
                new AssistantContent("旧答"),
                new UserContent("新问"),
                new AssistantContent("新答", new TokenUsage(50, 5, 55, null), "OPENAI/gpt"));

        HistoryUsageSummary summary = HistoryUsageSummary.of(history);

        String line = summary.summaryLine();
        assertTrue(line.contains("已记录 1 条回答用量"));
        assertTrue(line.contains("另有 1 条助手消息无 token 记录"));
        assertEquals(1, summary.getUnrecordedMessages());
        assertEquals(50L, summary.getRecorded().getInputTokens());
        assertFalse(line.contains("估算"));
    }

    /**
     * 完全没有记录时只提示暂无记录，不再按字符估算。
     */
    @Test
    public void reportsNoTokenRecordWhenNothingRecorded() {
        List<IContent> history = Arrays.asList(new UserContent("a"), new AssistantContent("b"));

        HistoryUsageSummary summary = HistoryUsageSummary.of(history);

        assertEquals(0L, summary.getRecorded().getCalls());
        assertTrue(summary.getByModel().isEmpty());
        assertEquals("历史记录：2 条，暂无 token 记录", summary.summaryLine());
    }

    /**
     * 空历史与全旧历史都不抛异常。
     */
    @Test
    public void handlesEmptyHistory() {
        assertEquals("历史记录：0 条，暂无 token 记录", HistoryUsageSummary.of(null).summaryLine());
        List<IContent> history = Collections.<IContent>singletonList(new AssistantContent("x"));
        assertEquals("历史记录：1 条，暂无 token 记录", HistoryUsageSummary.of(history).summaryLine());
    }

    /**
     * 旧消息有用量但没有模型字段时归入「未记录模型」，统计不丢。
     */
    @Test
    public void groupsMissingModelUnderPlaceholder() {
        List<IContent> history = Collections.<IContent>singletonList(
                new AssistantContent("x", new TokenUsage(1, 2, 3, null), null));

        HistoryUsageSummary summary = HistoryUsageSummary.of(history);

        assertEquals(1L, summary.getByModel().get("未记录模型").getCalls());
        assertEquals(3L, summary.getByModel().get("未记录模型").getTotalTokens());
    }
}
