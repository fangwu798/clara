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
 * {@link ToolOutputCompactor} 单元测试：历史与本轮工具结果都受预算约束，用户/助手消息不动。
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

    @Test
    public void keepsNewestWholeAndSplitsBudgetAcrossOlderResults() {
        ToolContent first = new ToolContent("call-1", repeat('a', 4000));
        ToolContent second = new ToolContent("call-2", repeat('b', 4000));
        ToolContent third = new ToolContent("call-3", repeat('c', 4000));
        ToolContent newest = new ToolContent("call-4", repeat('d', 4000));
        UserContent user = new UserContent("go");

        List<IContent> result = ToolOutputCompactor.compact(
                Arrays.<IContent>asList(first, second, user, third, newest), 4000, 8000);

        // 最新一条（4000 字）落在「最近保留预算」内，原样保留。
        assertSame(newest, result.get(4));
        // 其余结果按剩余预算分摊，每条不超过 8000 / 4 的一半，且保留省略标记。
        for (int index : new int[] {0, 1, 3}) {
            ToolContent compacted = (ToolContent) result.get(index);
            assertTrue(compacted.getContent().contains("已压缩"));
            assertTrue(compacted.getContent().length() < 4000);
        }
    }

    @Test
    public void capsSingleOversizedNewestResult() {
        ToolContent huge = new ToolContent("call-1", repeat('a', 10000));
        UserContent user = new UserContent("go");

        List<IContent> result = ToolOutputCompactor.compact(
                Arrays.<IContent>asList(user, huge), 4000, 1000);

        ToolContent compacted = (ToolContent) result.get(1);
        assertTrue(compacted.getContent().contains("已压缩"));
        assertTrue(compacted.getContent().length() < 1200);
    }

    @Test
    public void keepsHalfBudgetForCompactedNewestResult() {
        ToolContent older = new ToolContent("call-1", repeat('a', 5000));
        ToolContent newest = new ToolContent("call-2", repeat('b', 5000));

        List<IContent> result = ToolOutputCompactor.compact(
                Arrays.<IContent>asList(older, newest), 1000, 6000);

        int olderLength = ((ToolContent) result.get(0)).getContent().length();
        int newestLength = ((ToolContent) result.get(1)).getContent().length();
        // 最新一条独占总预算的一半（3000 字），其余结果按单条上限（1000 字）压缩。
        assertTrue(newestLength > olderLength);
        assertTrue(newestLength <= 3100);
        assertTrue(olderLength <= 1100);
    }

    @Test
    public void leavesResultsUntouchedWhenWithinBudget() {
        ToolContent first = new ToolContent("call-1", repeat('a', 100));
        ToolContent second = new ToolContent("call-2", repeat('b', 100));
        UserContent user = new UserContent("go");
        List<IContent> input = Arrays.<IContent>asList(first, user, second);

        assertSame(input, ToolOutputCompactor.compact(input, 4000, 10000));
    }

    @Test
    public void budgetAppliesToHistoryAndCurrentTurnAlike() {
        ToolContent history = new ToolContent("call-1", repeat('a', 5000));
        UserContent user = new UserContent("go");
        ToolContent first = new ToolContent("call-2", repeat('b', 5000));
        ToolContent second = new ToolContent("call-3", repeat('c', 5000));
        List<IContent> input = Arrays.<IContent>asList(history, user, first, second);

        List<IContent> result = ToolOutputCompactor.compact(input, 1000, 6000);

        // 最新一条 5000 字已超过「总预算的一半」，因此本轮结果同样进入压缩。
        assertTrue(((ToolContent) result.get(0)).getContent().contains("已压缩"));
        assertTrue(((ToolContent) result.get(2)).getContent().contains("已压缩"));
        assertTrue(((ToolContent) result.get(3)).getContent().contains("已压缩"));
    }

    private static String repeat(char value, int count) {
        char[] buffer = new char[count];
        Arrays.fill(buffer, value);
        return new String(buffer);
    }
}
