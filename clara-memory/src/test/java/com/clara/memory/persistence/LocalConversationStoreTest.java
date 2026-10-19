package com.clara.memory.persistence;

import com.clara.core.message.IContent;
import com.clara.exception.ClaraException;
import com.clara.kernel.message.delta.AssistantContent;
import com.clara.kernel.message.delta.UserContent;
import com.clara.memory.persistence.local.LocalConversationStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

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
}
