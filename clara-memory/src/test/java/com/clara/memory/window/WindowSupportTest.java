package com.clara.memory.window;

import com.clara.core.message.IContent;
import com.clara.dto.memory.WindowPolicy;
import com.clara.dto.tool.ToolCall;
import com.clara.dto.tool.ToolCallContent;
import com.clara.dto.tool.ToolCallFunction;
import com.clara.enums.RoleEnum;
import com.clara.kernel.message.delta.AssistantContent;
import com.clara.kernel.message.delta.SystemContent;
import com.clara.kernel.message.delta.ToolContent;
import com.clara.kernel.message.delta.UserContent;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link WindowSupport} 的单元测试：分组、system 常驻、三类上限与最近轮次下限。
 *
 * <p>窗口裁剪决定每次发给平台的历史规模，是 token 爆炸的最后一道闸门；这里覆盖裁剪决策本身，
 * 不依赖具体存储实现。
 */
public class WindowSupportTest {

    private static IContent system(String text) {
        return new SystemContent(text);
    }

    private static IContent user(String text) {
        return new UserContent(text);
    }

    private static IContent assistant(String text) {
        return new AssistantContent(text);
    }

    private static IContent toolCall() {
        ToolCallFunction function = new ToolCallFunction();
        function.setName("echo");
        function.setArguments("{}");
        ToolCall call = new ToolCall();
        call.setId("call-1");
        call.setType("function");
        call.setFunction(function);
        return new ToolCallContent(Collections.singletonList(call));
    }

    private static IContent toolResult(String id) {
        return new ToolContent(id, "{\"echo\":\"hi\"}");
    }

    private static List<String> texts(List<IContent> messages) {
        List<String> values = new ArrayList<>();
        for (IContent message : messages) {
            String text = message instanceof UserContent ? ((UserContent) message).getContent()
                    : ((AssistantContent) message).getContent();
            values.add(message.getRoleType() + ":" + text);
        }
        return values;
    }

    @Test
    public void rolePredicatesTolerateNullAndOtherRoles() {
        assertTrue(WindowSupport.isSystemInstruction(system("s")));
        assertFalse(WindowSupport.isSystemInstruction(user("u")));
        assertFalse(WindowSupport.isSystemInstruction(null));

        assertTrue(WindowSupport.startsToolRoundTrip(toolCall()));
        assertFalse(WindowSupport.startsToolRoundTrip(user("u")));
        assertFalse(WindowSupport.startsToolRoundTrip(null));

        assertTrue(WindowSupport.isToolResult(toolResult("call-1")));
        assertFalse(WindowSupport.isToolResult(assistant("a")));
        assertFalse(WindowSupport.isToolResult(null));
    }

    @Test
    public void groupRoundTripsKeepsToolResultsWithTheirCall() {
        List<IContent> body = Arrays.asList(
                user("u1"),
                toolCall(), toolResult("call-1"), toolResult("call-2"),
                assistant("a1"));

        List<List<IContent>> groups = WindowSupport.groupRoundTrips(body);

        assertEquals(3, groups.size());
        assertEquals(1, groups.get(0).size());
        assertEquals(3, groups.get(1).size());
        assertEquals(RoleEnum.TOOL_CALL, groups.get(1).get(0).getRoleType());
        assertEquals(1, groups.get(2).size());
    }

    @Test
    public void groupRoundTripsReturnsEmptyForNull() {
        assertTrue(WindowSupport.groupRoundTrips(null).isEmpty());
    }

    @Test
    public void groupConversationTurnsStartsNewGroupAtEachUserMessage() {
        List<IContent> body = Arrays.asList(
                system("stray"),
                user("u1"), assistant("a1"), toolCall(), toolResult("call-1"),
                user("u2"), assistant("a2"),
                user("u3"));

        List<List<IContent>> groups = WindowSupport.groupConversationTurns(body);

        assertEquals(4, groups.size());
        assertEquals(1, groups.get(0).size());
        assertEquals(4, groups.get(1).size());
        assertEquals(2, groups.get(2).size());
        assertEquals(1, groups.get(3).size());
    }

    @Test
    public void groupConversationTurnsWithoutUserGroupsEverythingTogether() {
        List<List<IContent>> groups = WindowSupport.groupConversationTurns(
                Arrays.asList(assistant("a1"), assistant("a2")));

        assertEquals(1, groups.size());
        assertEquals(2, groups.get(0).size());
        assertTrue(WindowSupport.groupConversationTurns(null).isEmpty());
    }

    @Test
    public void sizeBytesSumsMessagesAndStaysPositive() {
        long one = WindowSupport.sizeBytes(user("hello"));
        long two = WindowSupport.sizeBytes(Arrays.asList(user("hello"), assistant("world")));

        assertTrue(one > 0);
        assertEquals(one + WindowSupport.sizeBytes(assistant("world")), two);
        assertEquals(0L, WindowSupport.sizeBytes(Collections.<IContent>emptyList()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void trimRejectsNullMessages() {
        WindowSupport.trim(null, WindowPolicy.defaults(), null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void trimRejectsNullPolicy() {
        WindowSupport.trim(Collections.<IContent>emptyList(), null, null);
    }

    @Test
    public void trimKeepsSystemFirstAndDropsOldestTurnsByMessageCount() {
        List<IContent> messages = Arrays.asList(
                user("u1"), system("sys"), assistant("a1"), user("u2"), user("u3"));

        List<IContent> window = WindowSupport.trim(messages, new WindowPolicy(-1L, 3, -1L, 0), null);

        assertEquals(3, window.size());
        assertEquals(RoleEnum.SYSTEM, window.get(0).getRoleType());
        assertEquals(Arrays.asList("USER:u2", "USER:u3"), texts(window.subList(1, 3)));
    }

    @Test
    public void trimNeverDropsBelowRecentTurnsKept() {
        List<IContent> messages = Arrays.asList(
                system("sys"), user("u1"), assistant("a1"), user("u2"), assistant("a2"),
                user("u3"), assistant("a3"));

        // 条数上限 2 与「最近 2 轮必留」冲突时，轮次下限优先
        List<IContent> window = WindowSupport.trim(messages, new WindowPolicy(-1L, 2, -1L, 0, 2), null);

        assertEquals(5, window.size());
        assertEquals(RoleEnum.SYSTEM, window.get(0).getRoleType());
        assertEquals(Arrays.asList("USER:u2", "ASSISTANT:a2", "USER:u3", "ASSISTANT:a3"),
                texts(window.subList(1, 5)));
    }

    @Test
    public void trimBySizeDropsOldestMessages() {
        IContent sys = system("sys");
        IContent u2 = user("u2");
        IContent u3 = user("u3");
        long limit = WindowSupport.sizeBytes(Arrays.asList(sys, u2, u3));

        List<IContent> window = WindowSupport.trim(
                Arrays.asList(system("sys"), user("u1"), u2, u3),
                new WindowPolicy(-1L, -1, limit, 0), null);

        assertEquals(3, window.size());
        assertEquals(RoleEnum.SYSTEM, window.get(0).getRoleType());
        assertEquals(Arrays.asList("USER:u2", "USER:u3"), texts(window.subList(1, 3)));
    }

    @Test
    public void trimByTokenPercentUsesEstimatorBudget() {
        List<IContent> messages = Arrays.asList(
                system("sys"), user("u1"), user("u2"), user("u3"));

        // 预算 100 * 50% = 50，每条消息计 20 token：只允许留下 2 条（system + 最近一轮）
        List<IContent> window = WindowSupport.trim(messages, new WindowPolicy(100L, -1, -1L, 50),
                all -> all.size() * 20);

        assertEquals(2, window.size());
        assertEquals(RoleEnum.SYSTEM, window.get(0).getRoleType());
        assertEquals("USER:u3", texts(window.subList(1, 2)).get(0));
    }

    @Test
    public void trimIgnoresTokenPercentWhenEstimatorIsMissing() {
        List<IContent> messages = Arrays.asList(system("sys"), user("u1"), user("u2"));

        List<IContent> window = WindowSupport.trim(messages, new WindowPolicy(10L, -1, -1L, 1), null);

        assertEquals(3, window.size());
    }

    @Test
    public void trimReturnsUnmodifiableView() {
        List<IContent> window = WindowSupport.trim(
                Arrays.asList(system("sys"), user("u1")), WindowPolicy.defaults(), null);

        assertEquals(2, window.size());
        try {
            window.add(user("u2"));
            throw new AssertionError("window must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // 调用方不应改写窗口结果，避免污染底层历史
        }
    }
}