package com.clara.memory.persistence;

import com.clara.core.message.IContent;
import com.clara.enums.interaction.HistoryClearScopeEnum;
import com.clara.exception.ClaraException;
import com.clara.kernel.message.delta.AssistantContent;
import com.clara.kernel.message.delta.ToolContent;
import com.clara.kernel.message.delta.UserContent;
import com.clara.memory.persistence.local.LocalConversationStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    /**
     * 构造参数与目录占用：目录必填、上限必须为正、同一目录不能开两个存储。
     */
    @Test
    public void validatesConfigurationAndExclusiveLock() {
        assertThrows(IllegalArgumentException.class, () -> new LocalConversationStore(null));
        assertThrows(IllegalArgumentException.class,
                () -> new LocalConversationStore(tempFolder.getRoot().toPath(), 0L));
        assertThrows(ClaraException.class, () -> new LocalConversationStore(tempFolder.getRoot().toPath()));
    }

    /**
     * 写入参数校验：键与消息必填、空批次不改变版本、消息元素不得为 null。
     */
    @Test
    public void validatesAppendArguments() {
        assertThrows(IllegalArgumentException.class, () -> store.loadSnapshot(null));
        assertThrows(IllegalArgumentException.class, () -> store.append(null, messages(), 0L));
        assertThrows(IllegalArgumentException.class, () -> store.append(key, null, 0L));

        store.append(key, Collections.<IContent>emptyList(), 0L);
        assertEquals(0L, store.loadSnapshot(key).version());

        List<IContent> withNull = new ArrayList<>();
        withNull.add(new UserContent("u1"));
        withNull.add(null);
        assertThrows(IllegalArgumentException.class, () -> store.append(key, withNull, 0L));
    }

    /**
     * 超出单会话文件上限时拒绝写入，避免磁盘被单次对话占满。
     */
    @Test
    public void rejectsOversizedConversationFile() throws Exception {
        LocalConversationStore limited = new LocalConversationStore(tempFolder.newFolder("limited").toPath(), 16L);
        try {
            assertThrows(ClaraException.class, () -> limited.append(key, messages(), 0L));
        } finally {
            limited.close();
        }
    }

    /**
     * 清理参数校验与空目录语义。
     */
    @Test
    public void validatesDeleteArgumentsAndEmptyDirectory() {
        assertThrows(IllegalArgumentException.class, () -> store.deleteChatMessages(null, 1));
        assertThrows(IllegalArgumentException.class,
                () -> store.deleteChatMessages(HistoryClearScopeEnum.FIRST, 0));
        assertThrows(IllegalArgumentException.class,
                () -> store.deleteChatMessages(HistoryClearScopeEnum.LAST, -1));

        assertEquals(0, store.deleteChatMessages(HistoryClearScopeEnum.FIRST, 1));
        assertEquals(0, store.deleteChatMessages(HistoryClearScopeEnum.ALL, 0));
    }

    /**
     * ALL 范围直接删除全部历史文件，并返回删除前的聊天消息条数。
     */
    @Test
    public void deletesAllChatHistoryFiles() {
        store.append(key, chatMessages(), 0L);

        assertEquals(4, store.deleteChatMessages(HistoryClearScopeEnum.ALL, 0));

        assertTrue(store.loadAllSnapshots().isEmpty());
    }

    /**
     * 只有工具消息的会话不计入聊天清理范围，也不会被误删。
     */
    @Test
    public void ignoresConversationsWithoutChatMessages() {
        store.append(key, Collections.<IContent>singletonList(new ToolContent("call-1", "{}")), 0L);

        assertEquals(0, store.deleteChatMessages(HistoryClearScopeEnum.FIRST, 1));
        assertEquals(1, store.loadSnapshot(key).messages().size());
    }

    /**
     * 关闭后拒绝继续读写，重复关闭无副作用。
     */
    @Test
    public void closedStoreRejectsOperations() {
        store.close();
        store.close();

        assertThrows(IllegalStateException.class, () -> store.loadSnapshot(key));
    }
}