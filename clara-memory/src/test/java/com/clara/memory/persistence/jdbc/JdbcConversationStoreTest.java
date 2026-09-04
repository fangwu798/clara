package com.clara.memory.persistence.jdbc;

import org.junit.Test;

public class JdbcConversationStoreTest {
    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsafeTableIdentifierBeforeUsingDataSource() {
        new JdbcConversationStore(null, "conversation; DROP TABLE users");
    }
}
