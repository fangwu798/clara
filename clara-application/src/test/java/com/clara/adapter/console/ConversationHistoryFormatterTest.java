package com.clara.adapter.console;

import com.clara.core.message.IContent;
import com.clara.dto.tool.ToolResult;
import com.clara.kernel.message.delta.AssistantContent;
import com.clara.kernel.message.delta.TokenUsage;
import com.clara.kernel.message.delta.UserContent;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 聊天记录渲染：角色过滤、编号、空白折叠、截断与历史输入提取。
 */
public class ConversationHistoryFormatterTest {

    /**
     * 只渲染用户与助手消息，编号按过滤后的绝对位置计算。
     */
    @Test
    public void rendersChatMessagesOnly() {
        List<IContent> messages = Arrays.asList(
                new UserContent("第一问"),
                new AssistantContent("第一答"),
                new ToolResult("call-1", "ok"),
                new UserContent("  第二\n问  "),
                new AssistantContent("   "));

        assertEquals(3, ConversationHistoryFormatter.count(messages));
        List<String> lines = ConversationHistoryFormatter.render(messages, 0);
        assertEquals(3, lines.size());
        assertEquals("  [1] 用户：第一问", lines.get(0));
        assertEquals("  [2] 助手：第一答", lines.get(1));
        assertEquals("  [3] 用户：第二 问", lines.get(2));
    }

    /**
     * 助手消息展示本轮 token 用量，用户消息不展示。
     */
    @Test
    public void rendersAssistantTokenUsage() {
        List<IContent> messages = Arrays.asList(
                new UserContent("问"),
                new AssistantContent("答", new TokenUsage(1200, 300, 1500, null)));

        List<String> lines = ConversationHistoryFormatter.render(messages, 0);

        assertEquals("  [1] 用户：问", lines.get(0));
        assertEquals("  [2] 助手：答  [tokens 输入=1200，输出=300，合计=1500]", lines.get(1));
    }

    /**
     * 新历史同时展示回答所用的模型，便于确认每轮由哪个模型产生。
     */
    @Test
    public void rendersAssistantModelAndTokenUsage() {
        List<IContent> messages = Arrays.asList(
                new UserContent("问"),
                new AssistantContent("答", new TokenUsage(1200, 300, 1500, null), "DEEPSEEK/deepseek-chat"));

        List<String> lines = ConversationHistoryFormatter.render(messages, 0);

        assertEquals("  [2] 助手：答  [模型=DEEPSEEK/deepseek-chat，tokens 输入=1200，输出=300，合计=1500]",
                lines.get(1));
    }

    /**
     * 只渲染最近 N 条，但保留整体编号。
     */
    @Test
    public void rendersRecentMessagesWithAbsoluteNumbers() {
        List<IContent> messages = Arrays.asList(
                new UserContent("一"), new AssistantContent("二"),
                new UserContent("三"), new AssistantContent("四"));

        List<String> lines = ConversationHistoryFormatter.render(messages, 2);
        assertEquals(2, lines.size());
        assertEquals("  [3] 用户：三", lines.get(0));
        assertEquals("  [4] 助手：四", lines.get(1));
    }

    /**
     * 超长内容按字符数截断并追加省略号。
     */
    @Test
    public void truncatesLongMessages() {
        List<String> lines = ConversationHistoryFormatter.render(
                Collections.singletonList(new AssistantContent("0123456789")), 0, 5);
        assertEquals(1, lines.size());
        assertEquals("  [1] 助手：01234…", lines.get(0));
    }

    /**
     * 历史输入只取用户消息，保持时间顺序。
     */
    @Test
    public void extractsUserInputs() {
        List<IContent> messages = Arrays.asList(
                new UserContent("第一条"), new AssistantContent("回答"),
                new UserContent("  "), new ToolResult("call", "out"),
                new UserContent("第二条"));

        List<String> inputs = ConversationHistoryFormatter.userInputs(messages);
        assertEquals(Arrays.asList("第一条", "第二条"), inputs);
    }

    /**
     * 空历史不产生任何行。
     */
    @Test
    public void handlesEmptyHistory() {
        assertTrue(ConversationHistoryFormatter.render(null, 3).isEmpty());
        assertTrue(ConversationHistoryFormatter.render(Collections.emptyList(), 3).isEmpty());
        assertTrue(ConversationHistoryFormatter.userInputs(null).isEmpty());
        assertEquals(0, ConversationHistoryFormatter.count(null));
    }
}
