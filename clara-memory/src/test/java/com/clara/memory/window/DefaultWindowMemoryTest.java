package com.clara.memory.window;

import com.clara.core.message.IContent;
import com.clara.dto.memory.WindowPolicy;
import com.clara.enums.RoleEnum;
import com.clara.kernel.message.delta.AssistantContent;
import com.clara.kernel.message.delta.SystemContent;
import com.clara.kernel.message.delta.ToolContent;
import com.clara.kernel.message.delta.UserContent;
import com.clara.memory.persistence.InMemoryConversationStore;
import com.clara.tool.ToolCall;
import com.clara.tool.ToolCallContent;
import com.clara.tool.ToolCallFunction;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link DefaultWindowMemory} 的单元测试：消息顺序、按条数裁剪、system 常驻、工具往返整组淘汰与清空。
 */
public class DefaultWindowMemoryTest {

    private final InMemoryConversationStore store = new InMemoryConversationStore();

    private DefaultWindowMemory memory() {
        return new DefaultWindowMemory("conv-1", "provider", "model", store);
    }

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

    private static IContent toolResult() {
        return new ToolContent("call-1", "{\"echo\":\"hi\"}");
    }

    @Test
    public void appendKeepsMessageOrder() {
        DefaultWindowMemory memory = memory();
        memory.append(Arrays.asList(user("first"), assistant("second")));
        memory.append(Collections.singletonList(user("third")));
        List<IContent> messages = memory.messages();
        assertEquals(3, messages.size());
        assertEquals(RoleEnum.USER, messages.get(0).getRoleType());
        assertEquals("first", ((UserContent) messages.get(0)).getContent());
        assertEquals(RoleEnum.ASSISTANT, messages.get(1).getRoleType());
        assertEquals("third", ((UserContent) messages.get(2)).getContent());
    }

    @Test
    public void windowTrimsByMessageCount() {
        DefaultWindowMemory memory = memory();
        memory.append(Arrays.asList(system("sys"), user("u1"), user("u2"), user("u3")));
        WindowPolicy policy = new WindowPolicy(-1L, 3, -1L, 0);
        List<IContent> window = memory.window(policy, null);
        assertEquals(3, window.size());
        assertEquals(RoleEnum.SYSTEM, window.get(0).getRoleType());
        assertEquals("u2", ((UserContent) window.get(1)).getContent());
        assertEquals("u3", ((UserContent) window.get(2)).getContent());
    }

    @Test
    public void systemMessageAlwaysStaysFirst() {
        DefaultWindowMemory memory = memory();
        memory.append(Arrays.asList(system("sys"), user("u1")));
        WindowPolicy policy = new WindowPolicy(-1L, 1, -1L, 0);
        List<IContent> window = memory.window(policy, null);
        assertEquals(1, window.size());
        assertEquals(RoleEnum.SYSTEM, window.get(0).getRoleType());
    }

    @Test
    public void toolRoundTripIsEvictedAsWholeGroup() {
        DefaultWindowMemory memory = memory();
        memory.append(Arrays.asList(system("sys"), toolCall(), toolResult(), user("last")));
        WindowPolicy policy = new WindowPolicy(-1L, 3, -1L, 0);
        List<IContent> window = memory.window(policy, null);
        assertEquals(2, window.size());
        assertEquals(RoleEnum.SYSTEM, window.get(0).getRoleType());
        assertEquals(RoleEnum.USER, window.get(1).getRoleType());
        assertEquals("last", ((UserContent) window.get(1)).getContent());
    }

    @Test
    public void clearRemovesHistoryAndAllowsReappend() {
        DefaultWindowMemory memory = memory();
        memory.append(Collections.singletonList(user("a")));
        assertEquals(1, memory.messages().size());
        memory.clear();
        assertTrue(memory.messages().isEmpty());
        memory.append(Collections.singletonList(user("b")));
        assertEquals(1, memory.messages().size());
        assertEquals("b", ((UserContent) memory.messages().get(0)).getContent());
    }
}
