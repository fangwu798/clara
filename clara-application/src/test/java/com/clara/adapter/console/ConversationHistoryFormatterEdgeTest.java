package com.clara.adapter.console;

import com.clara.core.message.IContent;
import com.clara.kernel.message.delta.AssistantContent;
import com.clara.kernel.message.delta.TokenUsage;
import com.clara.kernel.message.delta.UserContent;
import com.clara.kernel.message.delta.UserVisionContent;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link ConversationHistoryFormatter} 的补充用例：多模态正文、默认长度与 token 兜底。
 *
 * <p>图片轮次的正文藏在 content 数组里，老历史可能没有 total tokens；
 * 这两条都会让 /history 出现空行或错误统计，因此单独覆盖。
 */
public class ConversationHistoryFormatterEdgeTest {

    @Test
    public void rendersTextPartOfVisionMessage() {
        List<IContent> messages = Collections.<IContent>singletonList(
                new UserVisionContent("看图说一句话", Collections.singletonList("http://a/b.png")));

        List<String> lines = ConversationHistoryFormatter.render(messages, 0);

        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("看图说一句话"));
    }

    @Test
    public void visionMessageWithoutTextIsFilteredOut() {
        List<IContent> messages = Collections.<IContent>singletonList(
                new UserVisionContent("  ", Collections.singletonList("http://a/b.png")));

        assertTrue(ConversationHistoryFormatter.render(messages, 0).isEmpty());
        assertEquals(0, ConversationHistoryFormatter.count(messages));
    }

    @Test
    public void nonPositiveMaxCharsFallsBackToDefault() {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < ConversationHistoryFormatter.DEFAULT_MAX_CHARS + 50; index++) {
            text.append('a');
        }

        List<String> lines = ConversationHistoryFormatter.render(
                Collections.<IContent>singletonList(new UserContent(text.toString())), 0, 0);

        assertEquals(1, lines.size());
        assertTrue(lines.get(0).endsWith("…"));
    }

    @Test
    public void usageWithoutTotalFallsBackToInputPlusOutput() {
        List<IContent> messages = Collections.<IContent>singletonList(
                new AssistantContent("答", new TokenUsage(10, 20, null, null)));

        List<String> lines = ConversationHistoryFormatter.render(messages, 0);

        assertTrue(lines.get(0).contains("合计=30"));
    }

    @Test
    public void zeroUsageProducesNoSuffix() {
        List<IContent> messages = Collections.<IContent>singletonList(
                new AssistantContent("答", new TokenUsage(0, 0, 0, null)));

        List<String> lines = ConversationHistoryFormatter.render(messages, 0);

        assertEquals("  [1] 助手：答", lines.get(0));
    }

    @Test
    public void userInputsIgnoresVisionMessagesWithoutText() {
        List<IContent> messages = Arrays.<IContent>asList(
                new UserVisionContent("  ", Collections.singletonList("http://a/b.png")),
                new UserContent("真实输入"));

        assertEquals(Collections.singletonList("真实输入"),
                ConversationHistoryFormatter.userInputs(messages));
    }
}