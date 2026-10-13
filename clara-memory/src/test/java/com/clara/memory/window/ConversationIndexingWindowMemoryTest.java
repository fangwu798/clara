package com.clara.memory.window;

import com.clara.core.memory.IEmbeddingClient;
import com.clara.core.memory.ISemanticMemoryStore;
import com.clara.core.message.IContent;
import com.clara.dto.memory.MemoryEntry;
import com.clara.dto.memory.MemoryMatch;
import com.clara.dto.memory.SemanticMemoryQuery;
import com.clara.enums.memory.MemorySourceTypeEnum;
import com.clara.kernel.message.delta.AssistantContent;
import com.clara.kernel.message.delta.UserContent;
import com.clara.memory.persistence.InMemoryConversationStore;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link ConversationIndexingWindowMemory} 的单元测试：追加即写入 {@code CONVERSATION} 来源条目、序号连续、
 * 空正文跳过、写入失败不阻断本轮、清空时按会话删除。
 */
public class ConversationIndexingWindowMemoryTest {

    private static final String CONVERSATION = "conv-1";

    private final RecordingStore store = new RecordingStore();
    private final IEmbeddingClient embedding = text -> new float[] {1f, 0f};

    private ConversationIndexingWindowMemory memory() {
        ConversationIndexingWindowMemory memory = new ConversationIndexingWindowMemory(
                new DefaultWindowMemory(CONVERSATION, "provider", "model", new InMemoryConversationStore()),
                embedding, store);
        return memory;
    }

    @Test
    public void appendIndexesEachMessageAsConversationEntry() {
        ConversationIndexingWindowMemory memory = memory();
        memory.append(Arrays.asList(user("hello"), assistant("hi")));

        assertEquals(1, store.upsertCalls);
        assertEquals(2, store.entries.size());
        MemoryEntry first = store.entries.get(0);
        assertEquals(MemorySourceTypeEnum.CONVERSATION, first.getSourceType());
        assertEquals(CONVERSATION, first.getConversationId());
        assertEquals("CONVERSATION:" + CONVERSATION + ":0", first.getId());
        assertEquals("user: hello", first.getContent());
        assertEquals("CONVERSATION:" + CONVERSATION + ":1", store.entries.get(1).getId());
        assertEquals("assistant: hi", store.entries.get(1).getContent());
    }

    @Test
    public void sequentialAppendsKeepIncreasingIndex() {
        ConversationIndexingWindowMemory memory = memory();
        memory.append(Arrays.asList(user("a"), user("b")));
        memory.append(Collections.<IContent>singletonList(user("c")));

        assertEquals(3, store.entries.size());
        assertEquals("CONVERSATION:" + CONVERSATION + ":0", store.entries.get(0).getId());
        assertEquals("CONVERSATION:" + CONVERSATION + ":1", store.entries.get(1).getId());
        assertEquals("CONVERSATION:" + CONVERSATION + ":2", store.entries.get(2).getId());
    }

    @Test
    public void emptyAppendDoesNotTouchStore() {
        ConversationIndexingWindowMemory memory = memory();
        memory.append(Collections.<IContent>emptyList());

        assertEquals(0, store.upsertCalls);
        assertTrue(store.entries.isEmpty());
    }

    @Test
    public void blankContentMessageIsSkipped() {
        ConversationIndexingWindowMemory memory = memory();
        memory.append(Collections.<IContent>singletonList(user("   ")));

        assertEquals(0, store.upsertCalls);
        assertTrue(store.entries.isEmpty());
        assertEquals(1, memory.messages().size());
    }

    @Test
    public void upsertFailureDoesNotPropagate() {
        store.failUpsert = true;
        ConversationIndexingWindowMemory memory = memory();
        memory.append(Collections.<IContent>singletonList(user("hello")));

        assertEquals(1, store.upsertCalls);
        assertTrue(store.entries.isEmpty());
        assertEquals("历史仍应写入，不受向量库失败影响", 1, memory.messages().size());
    }

    @Test
    public void clearDeletesConversationEntries() {
        ConversationIndexingWindowMemory memory = memory();
        memory.append(Collections.<IContent>singletonList(user("hello")));
        memory.clear();

        assertEquals(CONVERSATION, store.deletedConversation);
        assertTrue(memory.messages().isEmpty());
    }

    private static IContent user(String text) {
        return new UserContent(text);
    }

    private static IContent assistant(String text) {
        return new AssistantContent(text);
    }

    /**
     * 记录写入内容的假语义存储，用于断言装饰器写入了什么。
     */
    private static final class RecordingStore implements ISemanticMemoryStore {

        final List<MemoryEntry> entries = new ArrayList<MemoryEntry>();
        int upsertCalls = 0;
        boolean failUpsert = false;
        String deletedConversation = null;

        @Override
        public void upsert(List<MemoryEntry> values) {
            upsertCalls++;
            if (failUpsert) {
                throw new IllegalStateException("vector store unavailable");
            }
            entries.addAll(values);
        }

        @Override
        public List<MemoryMatch> search(SemanticMemoryQuery query) {
            return Collections.emptyList();
        }

        @Override
        public void deleteById(String entryId) {
        }

        @Override
        public void deleteByConversation(String conversationId) {
            deletedConversation = conversationId;
        }

        @Override
        public void close() {
        }
    }
}
