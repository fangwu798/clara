package com.clara.memory.persistence;

import com.clara.core.message.IContent;
import com.clara.enums.interaction.HistoryClearScopeEnum;
import com.clara.kernel.message.delta.AssistantContent;
import com.clara.kernel.message.delta.UserContent;
import com.clara.memory.persistence.local.LocalConversationStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 补充覆盖 {@link LocalConversationStore} 的损坏文件容错与写盘边界。
 *
 * <p>历史文件可能因为进程被杀而留下半截内容，这里的容错分支直接决定
 * /history 是否还能正常展示其它会话，因此单独覆盖。
 */
public class LocalConversationStoreExtraTest {

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

    private Path conversationsDir() {
        return tempFolder.getRoot().toPath().resolve(".clara").resolve("state").resolve("conversations");
    }

    /**
     * ALL 清理时损坏文件不应阻断删除，且不计入返回条数。
     */
    @Test
    public void deleteAllSkipsCorruptedFile() throws Exception {
        store.append(key, Arrays.<IContent>asList(new UserContent("u1"), new AssistantContent("a1")), 0L);
        Files.write(conversationsDir().resolve("broken.bin"), new byte[]{9, 9, 9});

        assertEquals(2, store.deleteChatMessages(HistoryClearScopeEnum.ALL, 0));
        assertTrue(store.loadAllSnapshots().isEmpty());
    }

    /**
     * 按条清理时损坏文件被忽略，正常文件仍按预期删除。
     */
    @Test
    public void deleteFirstToleratesCorruptedFile() throws Exception {
        store.append(key, Arrays.<IContent>asList(new UserContent("u1"), new AssistantContent("a1")), 0L);
        Path broken = conversationsDir().resolve("broken.bin");
        Files.write(broken, new byte[]{9, 9, 9});
        // 让损坏文件排在正常文件之前，确保容错分支被走到。
        Files.setLastModifiedTime(broken, java.nio.file.attribute.FileTime.fromMillis(1L));

        assertEquals(1, store.deleteChatMessages(HistoryClearScopeEnum.FIRST, 1));

        List<IContent> remaining = store.loadSnapshot(key).messages();
        assertEquals(1, remaining.size());
        assertEquals("a1", ((AssistantContent) remaining.get(0)).getContent());
    }

    /**
     * 恢复时间戳时非正数表示「没有可恢复的时间」，应直接跳过而不是写入 1970 年。
     */
    @Test
    public void restoreLastModifiedIgnoresNonPositiveMillis() throws Exception {
        Path file = conversationsDir().resolve("sample.bin");
        Files.write(file, new byte[]{1});
        Method restore = LocalConversationStore.class.getDeclaredMethod(
                "restoreLastModified", Path.class, long.class);
        restore.setAccessible(true);
        long before = Files.getLastModifiedTime(file).toMillis();

        restore.invoke(store, file, 0L);

        assertTrue(Files.getLastModifiedTime(file).toMillis() >= before - 1000L);
    }

    /**
     * 目标路径没有文件名属于调用方编程错误，应在写盘前直接拒绝。
     */
    @Test
    public void writeAtomicRejectsTargetWithoutFileName() throws Exception {
        Method writeAtomic = LocalConversationStore.class.getDeclaredMethod(
                "writeAtomic", Path.class, byte[].class);
        writeAtomic.setAccessible(true);
        Path root = tempFolder.getRoot().toPath().getRoot();
        InvocationTargetException error = assertThrows(InvocationTargetException.class,
                () -> writeAtomic.invoke(store, root, new byte[]{1}));
        assertTrue(error.getCause() instanceof IllegalArgumentException);
    }
}