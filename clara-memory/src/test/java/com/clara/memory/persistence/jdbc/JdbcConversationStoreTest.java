package com.clara.memory.persistence.jdbc;

import com.clara.core.message.IContent;
import com.clara.exception.ClaraException;
import com.clara.kernel.message.delta.AssistantContent;
import com.clara.kernel.message.delta.UserContent;
import com.clara.memory.persistence.ConversationKey;
import com.clara.memory.persistence.ConversationSnapshot;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * JDBC conversation store behaviour against an in-memory H2 database.
 */
public class JdbcConversationStoreTest {

    private JdbcDataSource dataSource;
    private JdbcConversationStore store;

    @Before
    public void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:conversation-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        execute("CREATE TABLE clara_conversation (store_key VARCHAR(256) PRIMARY KEY, "
                + "version BIGINT NOT NULL, payload BLOB NOT NULL)");
        store = new JdbcConversationStore(dataSource);
    }

    @Test
    public void validatesConstructionArguments() {
        assertIllegalArgument(() -> new JdbcConversationStore(null));
        assertIllegalArgument(() -> new JdbcConversationStore(dataSource, null));
        assertIllegalArgument(() -> new JdbcConversationStore(dataSource, "bad-table"));
        assertIllegalArgument(() -> new JdbcConversationStore(dataSource, "1table"));
        assertIllegalArgument(() -> new JdbcConversationStore(dataSource, ""));
        assertIllegalArgument(() -> new JdbcConversationStore(dataSource, "schema.table"));
    }

    @Test
    public void rejectsTableNamesContainingSqlMetacharacters() {
        String[] payloads = {
            "t; DROP TABLE clara_conversation",
            "t OR 1=1",
            "t--",
            "t)",
            "clara_conversation where 1=1",
        };
        for (String payload : payloads) {
            assertIllegalArgument(() -> new JdbcConversationStore(dataSource, payload));
        }
        String quoted = "t" + (char) 34 + " --";
        assertIllegalArgument(() -> new JdbcConversationStore(dataSource, quoted));
    }

    @Test
    public void loadsEmptySnapshotWhenRowIsMissing() {
        ConversationSnapshot snapshot = store.loadSnapshot(key("c1"));
        assertEquals(0L, snapshot.version());
        assertTrue(snapshot.messages().isEmpty());
    }

    @Test
    public void appendsInsertsAndUpdatesWithOptimisticVersions() {
        ConversationKey key = key("c1");
        store.append(key, Collections.<IContent>singletonList(new UserContent("one")), 0L);
        ConversationSnapshot first = store.loadSnapshot(key);
        assertEquals(1L, first.version());
        assertEquals(1, first.messages().size());

        store.append(key, Collections.<IContent>singletonList(new AssistantContent("two")), 1L);
        ConversationSnapshot second = store.loadSnapshot(key);
        assertEquals(2L, second.version());
        assertEquals(2, second.messages().size());
        assertTrue(second.messages().get(1) instanceof AssistantContent);
    }

    @Test
    public void rejectsVersionConflictForExistingRow() {
        ConversationKey key = key("c1");
        store.append(key, Collections.<IContent>singletonList(new UserContent("one")), 0L);
        try {
            store.append(key, Collections.<IContent>singletonList(new AssistantContent("two")), 0L);
            fail("expected version conflict");
        } catch (ClaraException expected) {
            assertEquals("CONVERSATION_VERSION_CONFLICT", expected.getError().getCode());
        }
    }

    @Test
    public void emptyAppendIsNoOpAndNullMessagesAreRejected() {
        ConversationKey key = key("c1");
        store.append(key, Collections.<IContent>emptyList(), 0L);
        assertEquals(0L, store.loadSnapshot(key).version());
        assertIllegalArgument(() -> store.append(key, null, 0L));

        List<IContent> withNull = Arrays.asList(new UserContent("a"), null);
        assertIllegalArgument(() -> store.append(key, withNull, 0L));
    }

    @Test
    public void requiresKeyOnEveryOperation() {
        assertIllegalArgument(() -> store.loadSnapshot(null));
        assertIllegalArgument(() -> store.append(null, Collections.<IContent>emptyList(), 0L));
        assertIllegalArgument(() -> store.clear(null));
    }

    @Test
    public void clearsAndClosesStore() {
        ConversationKey key = key("c1");
        store.append(key, Collections.<IContent>singletonList(new UserContent("one")), 0L);
        store.clear(key);
        assertEquals(0L, store.loadSnapshot(key).version());

        store.close();
        assertIllegalState(() -> store.loadSnapshot(key));
        assertIllegalState(() -> store.append(key, Collections.<IContent>emptyList(), 0L));
        assertIllegalState(() -> store.clear(key));
    }

    @Test
    public void mapsSqlFailuresToIoErrors() throws Exception {
        execute("DROP TABLE clara_conversation");
        try {
            store.loadSnapshot(key("c1"));
            fail("expected io error");
        } catch (ClaraException expected) {
            assertEquals("CONVERSATION_STORE_IO_ERROR", expected.getError().getCode());
        }
    }

    @Test
    public void rollsBackWhenPersistFails() throws Exception {
        execute("CREATE TABLE constrained (store_key VARCHAR(256) PRIMARY KEY, "
                + "version BIGINT NOT NULL CHECK (version < 1), payload BLOB NOT NULL)");
        JdbcConversationStore constrained = new JdbcConversationStore(dataSource, "constrained");
        try {
            constrained.append(key("c1"), Collections.<IContent>singletonList(new UserContent("one")), 0L);
            fail("expected io error");
        } catch (ClaraException expected) {
            assertEquals("CONVERSATION_STORE_IO_ERROR", expected.getError().getCode());
        }
        assertEquals(0L, constrained.loadSnapshot(key("c1")).version());
    }

    private static ConversationKey key(String id) {
        return new ConversationKey(id, "DEEPSEEK", "model");
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void assertIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void assertIllegalState(Runnable action) {
        try {
            action.run();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // expected
        }
    }
}
