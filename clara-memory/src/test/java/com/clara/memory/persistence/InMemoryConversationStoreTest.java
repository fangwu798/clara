package com.clara.memory.persistence;

import com.clara.core.message.IContent;
import com.clara.enums.interaction.HistoryClearScopeEnum;
import com.clara.kernel.message.delta.AssistantContent;
import com.clara.kernel.message.delta.ToolContent;
import com.clara.kernel.message.delta.UserContent;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link InMemoryConversationStore} 的单元测试：读写、清空与按范围删除聊天消息。
 */
public class InMemoryConversationStoreTest {

    private final InMemoryConversationStore store = new InMemoryConversationStore();
    private final ConversationKey first = new ConversationKey("conv-1", "provider", "model");
    private final ConversationKey second = new ConversationKey("conv-2", "provider", "model");

    /**
     * 追加后可读取快照，版本随追加递增，clear 后恢复为空。
     */
    @Test
    public void appendsLoadsAndClearsSnapshot() {
        store.append(first, messages("u1", "a1"), 0L);
        store.append(first, Collections.singletonList(new UserContent("u2")), 1L);

        ConversationSnapshot snapshot = store.loadSnapshot(first);
        assertEquals(2L, snapshot.version());
        assertEquals(3, snapshot.messages().size());
        assertEquals("u1", ((UserContent) snapshot.messages().get(0)).getContent());

        store.clear(first);
        assertEquals(0L, store.loadSnapshot(first).version());
        assertTrue(store.loadSnapshot(first).messages().isEmpty());
    }

    /**
     * 从最早的消息开始跨会话清理，只删除聊天消息并保留原有顺序。
     */
    @Test
    public void deletesOldestChatMessagesAcrossConversations() {
        store.append(first, messages("u1", "a1"), 0L);
        store.append(second, messages("u2", "a2"), 0L);

        assertEquals(2, store.deleteChatMessages(HistoryClearScopeEnum.FIRST, 2));

        List<IContent> firstRemaining = store.loadSnapshot(first).messages();
        assertTrue(firstRemaining.isEmpty());
        List<IContent> secondRemaining = store.loadSnapshot(second).messages();
        assertEquals(2, secondRemaining.size());
        assertEquals("u2", ((UserContent) secondRemaining.get(0)).getContent());
        assertEquals("a2", ((AssistantContent) secondRemaining.get(1)).getContent());
    }

    /**
     * 从最新的消息开始清理，跨越会话边界按时间倒序处理。
     */
    @Test
    public void deletesNewestChatMessagesAcrossConversations() {
        store.append(first, messages("u1", "a1"), 0L);
        store.append(second, messages("u2", "a2"), 0L);

        assertEquals(1, store.deleteChatMessages(HistoryClearScopeEnum.LAST, 1));

        assertEquals(2, store.loadSnapshot(first).messages().size());
        List<IContent> secondRemaining = store.loadSnapshot(second).messages();
        assertEquals(1, secondRemaining.size());
        assertEquals("u2", ((UserContent) secondRemaining.get(0)).getContent());
    }

    /**
     * 清理条数超过总量或指定 ALL 时清空全部会话。
     */
    @Test
    public void clearsAllWhenRequestedOrCountExceedsTotal() {
        store.append(first, messages("u1", "a1"), 0L);
        store.append(second, messages("u2", "a2"), 0L);

        assertEquals(4, store.deleteChatMessages(HistoryClearScopeEnum.FIRST, 99));
        assertTrue(store.loadSnapshot(first).messages().isEmpty());
        assertTrue(store.loadSnapshot(second).messages().isEmpty());

        store.append(first, messages("u1", "a1"), 0L);
        assertEquals(2, store.deleteChatMessages(HistoryClearScopeEnum.ALL, 0));
        assertTrue(store.loadSnapshot(first).messages().isEmpty());
    }

    /**
     * 空存储或全为非聊天消息时，按条清理不产生删除。
     */
    @Test
    public void returnsZeroWhenNoChatMessagesExist() {
        assertEquals(0, store.deleteChatMessages(HistoryClearScopeEnum.FIRST, 1));
        store.append(first, Collections.singletonList(new AssistantContent("")), 0L);
        assertEquals(0, store.deleteChatMessages(HistoryClearScopeEnum.LAST, 1));
    }

    /**
     * 非法参数与非法写入被拒绝。
     */
    @Test
    public void rejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> store.deleteChatMessages(null, 1));
        assertThrows(IllegalArgumentException.class,
                () -> store.deleteChatMessages(HistoryClearScopeEnum.FIRST, 0));
        assertThrows(IllegalArgumentException.class, () -> store.loadSnapshot(null));
        assertThrows(IllegalArgumentException.class, () -> store.clear(null));
        assertThrows(IllegalArgumentException.class,
                () -> store.append(null, Collections.singletonList(new UserContent("u1")), 0L));
        assertThrows(IllegalArgumentException.class, () -> store.append(first, null, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> store.append(first, Collections.singletonList(null), 0L));
    }

    private static List<IContent> messages(String user, String assistant) {
        return Arrays.asList(new UserContent(user), new AssistantContent(assistant));
    }

    /**
     * 未知会话返回空快照；版本冲突被拒绝；空批次仍推进版本。
     */
    @Test
    public void handlesUnknownKeysConflictsAndEmptyBatches() {
        assertEquals(0L, store.loadSnapshot(first).version());
        assertTrue(store.loadSnapshot(first).messages().isEmpty());

        store.append(first, messages("u1", "a1"), 0L);
        assertThrows(com.clara.exception.ClaraException.class,
                () -> store.append(first, messages("u2", "a2"), 0L));

        store.append(first, Collections.<IContent>emptyList(), 1L);
        assertEquals(2L, store.loadSnapshot(first).version());
        assertEquals(2, store.loadSnapshot(first).messages().size());
    }

    /**
     * 多模态内容按正文判断是否为聊天消息；只有工具消息的会话在清理后整体移除。
     */
    @Test
    public void identifiesChatMessagesByRenderedContent() {
        store.append(first, Collections.<IContent>singletonList(new ToolContent("call-1", "{}")), 0L);
        assertEquals(0, store.deleteChatMessages(HistoryClearScopeEnum.FIRST, 1));

        store.append(first, Arrays.<IContent>asList(new UserContent("u1"), new ToolContent("call-1", "{}")), 1L);
        assertEquals(1, store.deleteChatMessages(HistoryClearScopeEnum.FIRST, 1));

        assertTrue(store.loadSnapshot(first).messages().isEmpty());
    }

    /**
     * close 清空全部会话，之后读取为空快照。
     */
    @Test
    public void closeClearsAllSnapshots() {
        store.append(first, messages("u1", "a1"), 0L);

        store.close();

        assertEquals(0L, store.loadSnapshot(first).version());
        assertTrue(store.loadSnapshot(first).messages().isEmpty());
    }
}