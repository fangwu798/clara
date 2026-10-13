package com.clara.orchestration.context;

import com.clara.core.message.IContent;
import com.clara.kernel.message.delta.AssistantContent;
import com.clara.kernel.message.delta.ToolContent;
import com.clara.kernel.message.delta.UserContent;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link ToolOutputCompactor} 单元测试：只压缩当前提问之前的历史工具结果，且不动用户/助手消息。
 */
public class ToolOutputCompactorTest {

    @Test
    public void compactsHistoricalToolResultAndKeepsToolCallId() {
        ToolContent huge = new ToolContent("call-1", repeat('a', 100));
        UserContent current = new UserContent("now");
        ToolContent fresh = new ToolContent("call-2", repeat('b', 100));

        List<IContent> result = ToolOutputCompactor.compact(
                Arrays.<IContent>asList(huge, current, fresh), 20);

        ToolContent compacted = (ToolContent) result.get(0);
        assertEquals("call-1", compacted.getToolCallId());
        assertTrue(compacted.getContent().length() < huge.getContent().length());
        assertTrue(compacted.getContent().contains("已压缩"));
        // 本轮（最后一条用户消息之后）的工具结果原样保留。
        assertSame(fresh, result.get(2));
        // 用户消息不被改写。
        assertSame(current, result.get(1));
    }

    @Test
    public void keepsShortToolResultAndOtherRolesUntouched() {
        ToolContent shortTool = new ToolContent("call-1", "short");
        AssistantContent assistant = new AssistantContent("answer");
        UserContent user = new UserContent("q");

        List<IContent> input = Arrays.<IContent>asList(shortTool, assistant, user);
        List<IContent> result = ToolOutputCompactor.compact(input, 1000);

        assertSame(input, result);
        assertSame(shortTool, result.get(0));
    }

    @Test
    public void returnsOriginalListWhenNothingToCompact() {
        UserContent user = new UserContent("only-user");
        List<IContent> input = Arrays.<IContent>asList(user);

        assertSame(input, ToolOutputCompactor.compact(input, 10));
    }

    @Test
    public void doesNotCompactWhenDisabledOrBoundaryMissing() {
        ToolContent huge = new ToolContent("call-1", repeat('a', 100));
        List<IContent> noUser = Arrays.<IContent>asList(huge);

        // maxChars <= 0 表示禁用。
        assertSame(noUser, ToolOutputCompactor.compact(noUser, 0));
        // 无用户消息（找不到历史边界）时不压缩，保守处理。
        assertSame(noUser, ToolOutputCompactor.compact(noUser, 10));
    }

    private static String repeat(char value, int count) {
        char[] buffer = new char[count];
        Arrays.fill(buffer, value);
        return new String(buffer);
    }
}
