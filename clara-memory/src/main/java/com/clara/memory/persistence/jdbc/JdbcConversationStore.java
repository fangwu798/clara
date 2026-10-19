package com.clara.memory.persistence.jdbc;

import com.clara.core.message.IContent;
import com.clara.memory.persistence.ConversationCodec;
import com.clara.memory.persistence.ConversationKey;
import com.clara.memory.persistence.ConversationSnapshot;
import com.clara.memory.persistence.ConversationStore;
import com.clara.memory.persistence.ConversationStoreErrors;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JDBC 会话存储：通过 JDBC 持久化会话快照，用于多实例共享部署。
 *
 * <p>以 {@code store_key} 为主键的简单单表实现；{@code append} 用
 * {@code UPDATE ... WHERE version = expectedVersion} 做乐观并发，未命中且行不存在时插入。
 * 需要调用方注入 {@link DataSource} 并保证目标表已存在。
 */
public final class JdbcConversationStore implements ConversationStore {

    /**
     * 默认表名。
     */
    public static final String DEFAULT_TABLE = "clara_conversation";

    private final DataSource dataSource;
    private final String table;
    private final ConversationCodec codec = new ConversationCodec();
    private volatile boolean closed = false;

    /**
     * 使用默认表名创建 JDBC 会话存储。
     *
     * @param dataSource 数据源，不可为 null
     */
    public JdbcConversationStore(DataSource dataSource) {
        this(dataSource, DEFAULT_TABLE);
    }

    /**
     * 创建 JDBC 会话存储。
     *
     * @param dataSource 数据源，不可为 null
     * @param table      表名，必须是简单标识符
     */
    public JdbcConversationStore(DataSource dataSource, String table) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource must not be null");
        }
        if (table == null || !table.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("table must be a simple identifier");
        }
        this.dataSource = dataSource;
        this.table = table;
    }

    @Override
    public ConversationSnapshot loadSnapshot(ConversationKey key) {
        open();
        requireKey(key);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT version, payload FROM " + table + " WHERE store_key = ?")) {
            statement.setString(1, key.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return new ConversationSnapshot(0L, Collections.<IContent>emptyList());
                }
                long version = resultSet.getLong(1);
                byte[] payload = resultSet.getBytes(2);
                return codec.decode(payload);
            }
        } catch (SQLException e) {
            throw ConversationStoreErrors.io();
        }
    }

    @Override
    public void append(ConversationKey key, List<IContent> messages, long expectedVersion) {
        open();
        requireKey(key);
        if (messages == null) {
            throw new IllegalArgumentException("messages must not be null");
        }
        if (messages.isEmpty()) {
            return;
        }
        ConversationSnapshot current = loadSnapshot(key);
        if (current.version() != expectedVersion) {
            throw ConversationStoreErrors.versionConflict();
        }
        List<IContent> merged = new ArrayList<>(current.messages().size() + messages.size());
        merged.addAll(current.messages());
        for (IContent message : messages) {
            if (message == null) {
                throw new IllegalArgumentException("messages must not contain null");
            }
            merged.add(message);
        }
        ConversationSnapshot next = new ConversationSnapshot(expectedVersion + 1L, merged);
        byte[] payload = codec.encode(next);
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                int updated;
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE " + table + " SET version = ?, payload = ? WHERE store_key = ? AND version = ?")) {
                    update.setLong(1, next.version());
                    update.setBytes(2, payload);
                    update.setString(3, key.value());
                    update.setLong(4, expectedVersion);
                    updated = update.executeUpdate();
                }
                if (updated == 0) {
                    if (rowExists(connection, key.value())) {
                        throw ConversationStoreErrors.versionConflict();
                    }
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO " + table + " (store_key, version, payload) VALUES (?, ?, ?)")) {
                        insert.setString(1, key.value());
                        insert.setLong(2, next.version());
                        insert.setBytes(3, payload);
                        insert.executeUpdate();
                    }
                }
                connection.commit();
            } catch (SQLException e) {
                rollbackQuietly(connection);
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw ConversationStoreErrors.io();
        }
    }

    @Override
    public void clear(ConversationKey key) {
        open();
        requireKey(key);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM " + table + " WHERE store_key = ?")) {
            statement.setString(1, key.value());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw ConversationStoreErrors.io();
        }
    }

    @Override
    public void close() {
        closed = true;
    }

    private boolean rowExists(Connection connection, String keyValue) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM " + table + " WHERE store_key = ?")) {
            statement.setString(1, keyValue);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private void open() {
        if (closed) {
            throw new IllegalStateException("conversation store is closed");
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 回滚失败不阻断异常传播
        }
    }

    private static void requireKey(ConversationKey key) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
    }
}
