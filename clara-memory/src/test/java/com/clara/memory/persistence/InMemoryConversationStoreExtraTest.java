package com.clara.memory.persistence;

import com.clara.core.message.IContent;
import com.clara.enums.interaction.HistoryClearScopeEnum;
import com.clara.kernel.message.delta.AssistantContent;
import com.clara.kernel.message.delta.ToolContent;
import com.clara.kernel.message.delta.UserContent;
import com.clara.kernel.message.delta.UserVisionContent;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 补充覆盖 {@link InMemoryConversationStore} 中「多模态内容判定」与
 * 「清理后整条会话移除」这两条容易遗漏的分支。
 */
public class InMemoryConversationStoreExtraTest {

    private final InMemoryConversationStore store = new InMemoryConversationStore();
    private final ConversationKey first = new ConversationKey("conv-1", "provider", "model");
    private final ConversationKey second = new ConversationKey("conv-2", "provider", "model");

    /**
     * 前一个会话已经删满目标条数时，后续会话应直接按 0 条处理。
     */
    @Test
    public void stopsRemovingWhenCountAlreadySatisfied() {
        store.append(first, Arrays.<IContent>asList(new UserContent("u1")), 0L);
        store.append(second, Arrays.<IContent>asList(new UserContent("u2")), 0L);

        assertEquals(1, store.deleteChatMessages(HistoryClearScopeEnum.FIRST, 1));

        assertEquals(0, store.loadSnapshot(first).messages().size());
        assertEquals(1, store.loadSnapshot(second).messages().size());
    }

    /**
     * 删除后只剩工具消息时不保留空壳会话，避免 /history 出现无内容记录。
     */
    @Test
    public void removesConversationWhenOnlyToolMessagesRemain() {
        store.append(first, Arrays.<IContent>asList(
                new UserContent("u1"), new ToolContent("call-1", "{}")), 0L);

        assertEquals(1, store.deleteChatMessages(HistoryClearScopeEnum.FIRST, 1));

        assertTrue(store.loadSnapshot(first).messages().isEmpty());
        assertTrue(store.loadSnapshot(first).version() == 0L);
    }

    /**
     * 多模态消息的正文藏在 List 分片里，需要按 text 分片判定为聊天消息。
     */
    @Test
    public void recognizesVisionContentAsChatMessage() {
        store.append(first, Collections.<IContent>singletonList(
                new UserVisionContent("描述这张图", Collections.singletonList("http://example.com/a.png"))), 0L);

        assertEquals(1, store.deleteChatMessages(HistoryClearScopeEnum.LAST, 1));
        assertTrue(store.loadSnapshot(first).messages().isEmpty());
    }

    /**
     * 只有图片、没有文本的多模态消息不计入聊天消息（避免清空历史时误删图片轮次）。
     */
    @Test
    public void visionContentWithoutTextIsNotChatMessage() {
        store.append(first, Collections.<IContent>singletonList(
                new UserVisionContent("  ", Collections.singletonList("http://example.com/a.png"))), 0L);

        assertEquals(0, store.deleteChatMessages(HistoryClearScopeEnum.FIRST, 1));
        assertEquals(1, store.loadSnapshot(first).messages().size());
    }

    /**
     * 空助手消息与纯空白字符串都不算有效聊天记录。
     */
    @Test
    public void blankAssistantContentIsIgnored() {
        store.append(first, Arrays.<IContent>asList(
                new AssistantContent("   "), new AssistantContent("ok")), 0L);

        assertEquals(1, store.deleteChatMessages(HistoryClearScopeEnum.FIRST, 5));
        assertTrue(store.loadSnapshot(first).messages().isEmpty());
    }
}