package com.clara.memory.persistence;

import com.clara.core.message.IContent;
import com.clara.enums.interaction.HistoryClearScopeEnum;
import com.clara.exception.ClaraException;
import com.clara.kernel.message.delta.AssistantContent;
import com.clara.kernel.message.delta.UserContent;
import com.clara.memory.persistence.local.LocalConversationStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link LocalConversationStore} 的单元测试：append / loadSnapshot 往返、乐观并发冲突与 clear。
 */
public class LocalConversationStoreTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private LocalConversationStore store;
    private final ConversationKey key = new ConversationKey("conv-1", "provider", "model");

    @Before
    public void setUp() {
        store = new LocalConversationStore(tempFolder.getRoot().toPath());
    }

    @After
    public void tearDown() {
        store.close();
    }

    private static List<IContent> messages() {
        return Arrays.asList(new UserContent("hello"), new AssistantContent("hi there"));
    }

    @Test
    public void appendThenLoadSnapshotRoundTrip() {
        store.append(key, messages(), 0L);
        ConversationSnapshot snapshot = store.loadSnapshot(key);
        assertEquals(1L, snapshot.version());
        assertEquals(2, snapshot.messages().size());
        assertEquals("hello", ((UserContent) snapshot.messages().get(0)).getContent());
        assertEquals("hi there", ((AssistantContent) snapshot.messages().get(1)).getContent());
    }

    @Test
    public void appendWithStaleVersionThrowsConflict() {
        store.append(key, messages(), 0L);
        List<IContent> late = Collections.singletonList(new UserContent("late"));
        assertThrows(ClaraException.class, () -> store.append(key, late, 0L));
    }

    @Test
    public void clearResetsSnapshot() {
        store.append(key, messages(), 0L);
        store.clear(key);
        ConversationSnapshot snapshot = store.loadSnapshot(key);
        assertEquals(0L, snapshot.version());
        assertTrue(snapshot.messages().isEmpty());
    }

    /**
     * 枚举全部快照：跨「供应商 + 模型」分文件的历史都能读到，损坏文件跳过。
     */
    @Test
    public void loadsAllSnapshotsAcrossKeys() throws Exception {
        store.append(key, messages(), 0L);
        Thread.sleep(20L);
        ConversationKey other = new ConversationKey("conv-1", "provider", "model-2");
        store.append(other, Collections.singletonList(new UserContent("from other model")), 0L);
        Path directory = tempFolder.getRoot().toPath().resolve(".clara").resolve("state").resolve("conversations");
        Files.write(directory.resolve("broken.bin"), new byte[]{1, 2, 3});

        List<ConversationSnapshot> snapshots = store.loadAllSnapshots();
        assertEquals(2, snapshots.size());
        assertEquals(2, snapshots.get(0).messages().size());
        assertEquals("from other model", ((UserContent) snapshots.get(1).messages().get(0)).getContent());
    }

    /**
     * 按最早条数清理：只删除聊天消息，其余消息保持原顺序。
     */
    @Test
    public void deletesOldestChatMessages() {
        store.append(key, chatMessages(), 0L);

        assertEquals(1, store.deleteChatMessages(HistoryClearScopeEnum.FIRST, 1));

        List<IContent> remaining = store.loadSnapshot(key).messages();
        assertEquals(3, remaining.size());
        assertEquals("a1", ((AssistantContent) remaining.get(0)).getContent());
        assertEquals("u2", ((UserContent) remaining.get(1)).getContent());
        assertEquals("a2", ((AssistantContent) remaining.get(2)).getContent());
    }

    /**
     * 按最新条数清理：从末尾向前删除指定条数。
     */
    @Test
    public void deletesNewestChatMessages() {
        store.append(key, chatMessages(), 0L);

        assertEquals(2, store.deleteChatMessages(HistoryClearScopeEnum.LAST, 2));

        List<IContent> remaining = store.loadSnapshot(key).messages();
        assertEquals(2, remaining.size());
        assertEquals("u1", ((UserContent) remaining.get(0)).getContent());
        assertEquals("a1", ((AssistantContent) remaining.get(1)).getContent());
    }

    /**
     * 条数超过总量时等价于清空，并返回实际清理条数。
     */
    @Test
    public void clearsAllWhenCountExceedsTotal() {
        store.append(key, chatMessages(), 0L);

        assertEquals(4, store.deleteChatMessages(HistoryClearScopeEnum.FIRST, 99));

        assertTrue(store.loadSnapshot(key).messages().isEmpty());
        assertTrue(store.loadAllSnapshots().isEmpty());
    }

    private static List<IContent> chatMessages() {
        return Arrays.asList(new UserContent("u1"), new AssistantContent("a1"),
                new UserContent("u2"), new AssistantContent("a2"));
    }
}
