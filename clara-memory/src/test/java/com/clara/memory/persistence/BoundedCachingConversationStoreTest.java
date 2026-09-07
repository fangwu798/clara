package com.clara.memory.persistence;

import com.clara.memory.api.ConversationSnapshot;
import com.clara.memory.api.ConversationStore;
import com.clara.memory.persistence.local.InMemoryConversationStore;
import com.clara.memory.persistence.local.LocalConversationStore;
import com.clara.model.api.ChatMessage;
import com.clara.model.api.Content;
import com.clara.model.api.Role;
import com.clara.model.api.TextContent;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BoundedCachingConversationStoreTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void loadsOnlyWhenRequestedAndEvictsLeastRecentlyUsedConversation() {
        CountingStore delegate = new CountingStore();
        delegate.append("a", messages("a"), 0L);
        delegate.append("b", messages("b"), 0L);
        delegate.append("c", messages("c"), 0L);
        BoundedCachingConversationStore store =
                new BoundedCachingConversationStore(delegate, 2, 10L);

        assertEquals(0, delegate.loads("a"));
        store.load("a");
        store.load("b");
        store.load("a");
        store.load("c");
        store.load("b");

        assertEquals(1, delegate.loads("a"));
        assertEquals(2, delegate.loads("b"));
        assertEquals(1, delegate.loads("c"));
    }

    @Test
    public void doesNotRetainConversationLargerThanMessageLimit() {
        CountingStore delegate = new CountingStore();
        delegate.append("large", messages("one", "two", "three"), 0L);
        BoundedCachingConversationStore store =
                new BoundedCachingConversationStore(delegate, 2, 2L);

        assertEquals(3, store.load("large").size());
        assertEquals(3, store.load("large").size());

        assertEquals(2, delegate.loads("large"));
    }

    @Test
    public void invalidatesCachedSnapshotAfterAppendAndClear() {
        CountingStore delegate = new CountingStore();
        delegate.append("conversation", messages("one"), 0L);
        BoundedCachingConversationStore store =
                new BoundedCachingConversationStore(delegate, 2, 10L);

        ConversationSnapshot first = store.loadSnapshot("conversation");
        store.append("conversation", messages("two"), first.getVersion());
        assertEquals(2, store.load("conversation").size());
        store.clear("conversation");
        assertTrue(store.load("conversation").isEmpty());
        assertEquals(3, delegate.loads("conversation"));
    }

    @Test
    public void restoresPersistedConversationAfterReopeningWithoutPreloading() throws Exception {
        Path workspace = temporaryFolder.newFolder("workspace").toPath();
        BoundedCachingConversationStore first = new BoundedCachingConversationStore(
                new LocalConversationStore(workspace), 4, 20L);
        first.append("conversation", messages("persisted"), 0L);
        first.close();

        BoundedCachingConversationStore reopened = new BoundedCachingConversationStore(
                new LocalConversationStore(workspace), 4, 20L);
        try {
            assertEquals("persisted", text(reopened.load("conversation").get(0)));
        } finally {
            reopened.close();
        }
    }

    @Test
    public void closesOwnedDelegateAndRejectsFurtherAccess() throws Exception {
        CountingStore delegate = new CountingStore();
        BoundedCachingConversationStore store =
                new BoundedCachingConversationStore(delegate, 2, 10L);

        store.close();
        assertTrue(delegate.closed);
        try {
            store.load("conversation");
            fail("expected closed store failure");
        } catch (IllegalStateException expected) {
            assertEquals("conversation store is closed", expected.getMessage());
        }
    }

    private static List<ChatMessage> messages(String... values) {
        ChatMessage[] messages = new ChatMessage[values.length];
        for (int i = 0; i < values.length; i++) {
            messages[i] = new ChatMessage(Role.USER,
                    Collections.<Content>singletonList(new TextContent(values[i])),
                    null, Collections.<String, String>emptyMap());
        }
        return Arrays.asList(messages);
    }

    private static String text(ChatMessage message) {
        return ((TextContent) message.getContents().get(0)).getText();
    }

    private static final class CountingStore implements ConversationStore, Closeable {
        private final InMemoryConversationStore delegate = new InMemoryConversationStore();
        private final Map<String, Integer> loads = new LinkedHashMap<String, Integer>();
        private boolean closed;

        @Override
        public List<ChatMessage> load(String conversationId) {
            return loadSnapshot(conversationId).getMessages();
        }

        @Override
        public ConversationSnapshot loadSnapshot(String conversationId) {
            Integer count = loads.get(conversationId);
            loads.put(conversationId, Integer.valueOf(count == null ? 1 : count.intValue() + 1));
            return delegate.loadSnapshot(conversationId);
        }

        @Override
        public void append(String conversationId, List<ChatMessage> messages,
                long expectedVersion) {
            delegate.append(conversationId, messages, expectedVersion);
        }

        @Override
        public void clear(String conversationId) {
            delegate.clear(conversationId);
        }

        @Override
        public void close() throws IOException {
            closed = true;
        }

        private int loads(String conversationId) {
            Integer count = loads.get(conversationId);
            return count == null ? 0 : count.intValue();
        }
    }
}
