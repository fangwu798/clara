package com.clara.memory.persistence.jdbc;

import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCategory;
import com.clara.kernel.ErrorCodes;
import com.clara.memory.api.ConversationSnapshot;
import com.clara.memory.api.ConversationStore;
import com.clara.memory.persistence.ConversationCodec;
import com.clara.memory.persistence.ConversationStoreErrors;
import com.clara.model.api.ChatMessage;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.sql.DataSource;

public final class JdbcConversationStore implements ConversationStore {
    private final DataSource dataSource;
    private final String table;
    private final ConversationCodec codec = new ConversationCodec();

    public JdbcConversationStore(DataSource dataSource) {
        this(dataSource, "clara_conversation");
    }

    public JdbcConversationStore(DataSource dataSource, String table) {
        if (table == null || !table.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("table must be a simple SQL identifier");
        }
        if (dataSource == null) throw new IllegalArgumentException("dataSource must not be null");
        this.dataSource = dataSource;
        this.table = table;
    }

    @Override
    public List<ChatMessage> load(String conversationId) {
        return loadSnapshot(conversationId).getMessages();
    }

    @Override
    public ConversationSnapshot loadSnapshot(String conversationId) {
        requireId(conversationId);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(selectSql())) {
            statement.setString(1, conversationId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? decode(conversationId, result.getLong(1), result.getBytes(2))
                        : emptySnapshot();
            }
        } catch (SQLException exception) {
            throw ioError();
        }
    }

    @Override
    public void append(String conversationId, List<ChatMessage> messages, long expectedVersion) {
        requireId(conversationId);
        requireMessages(messages);
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            ConversationSnapshot current = loadForUpdate(connection, conversationId);
            if (current.getVersion() != expectedVersion) throw versionConflict();
            List<ChatMessage> combined = new ArrayList<ChatMessage>(current.getMessages());
            combined.addAll(messages);
            byte[] payload = codec.encode(conversationId,
                    new ConversationSnapshot(expectedVersion + 1L, combined));
            int changed = current.getVersion() == 0L
                    ? insert(connection, conversationId, payload)
                    : update(connection, conversationId, expectedVersion, payload);
            if (changed != 1) throw versionConflict();
            connection.commit();
        } catch (ClaraException exception) {
            rollback(connection);
            throw exception;
        } catch (IOException exception) {
            rollback(connection);
            throw corrupted();
        } catch (SQLException exception) {
            rollback(connection);
            if (isConstraintViolation(exception)) throw versionConflict();
            throw ioError();
        } finally {
            close(connection);
        }
    }

    @Override
    public void clear(String conversationId) {
        requireId(conversationId);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM " + table + " WHERE conversation_id = ?")) {
            statement.setString(1, conversationId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw ioError();
        }
    }

    private ConversationSnapshot loadForUpdate(Connection connection, String conversationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(selectSql() + " FOR UPDATE")) {
            statement.setString(1, conversationId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? decode(conversationId, result.getLong(1), result.getBytes(2))
                        : emptySnapshot();
            }
        }
    }

    private int insert(Connection connection, String conversationId, byte[] payload)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + table
                + " (conversation_id, version, payload) VALUES (?, ?, ?)")) {
            statement.setString(1, conversationId);
            statement.setLong(2, 1L);
            statement.setBytes(3, payload);
            return statement.executeUpdate();
        }
    }

    private int update(Connection connection, String conversationId, long expectedVersion,
            byte[] payload) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE " + table
                + " SET version = ?, payload = ? WHERE conversation_id = ? AND version = ?")) {
            statement.setLong(1, expectedVersion + 1L);
            statement.setBytes(2, payload);
            statement.setString(3, conversationId);
            statement.setLong(4, expectedVersion);
            return statement.executeUpdate();
        }
    }

    private ConversationSnapshot decode(String conversationId, long databaseVersion, byte[] payload) {
        try {
            ConversationSnapshot snapshot = codec.decode(conversationId, payload);
            if (snapshot.getVersion() != databaseVersion) throw corrupted();
            return snapshot;
        } catch (IOException exception) {
            throw corrupted();
        }
    }

    private String selectSql() {
        return "SELECT version, payload FROM " + table + " WHERE conversation_id = ?";
    }

    private static ConversationSnapshot emptySnapshot() {
        return new ConversationSnapshot(0L, Collections.<ChatMessage>emptyList());
    }
    private static void requireId(String conversationId) {
        if (conversationId == null || conversationId.trim().isEmpty()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
    }
    private static void requireMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        if (messages.contains(null)) throw new IllegalArgumentException("messages must not contain null");
    }
    private static ClaraException versionConflict() {
        return ConversationStoreErrors.error(ErrorCodes.CONVERSATION_VERSION_CONFLICT,
                ErrorCategory.CONFLICT, false);
    }
    private static ClaraException corrupted() {
        return ConversationStoreErrors.error(ErrorCodes.CONVERSATION_STORE_CORRUPTED,
                ErrorCategory.INTERNAL, false);
    }
    private static ClaraException ioError() {
        return ConversationStoreErrors.error(ErrorCodes.CONVERSATION_STORE_IO_ERROR,
                ErrorCategory.EXTERNAL_SERVICE, true);
    }
    private static void rollback(Connection connection) {
        if (connection == null) return;
        try { connection.rollback(); } catch (SQLException ignored) { }
    }
    private static void close(Connection connection) {
        if (connection == null) return;
        try { connection.close(); } catch (SQLException ignored) { }
    }
    private static boolean isConstraintViolation(SQLException exception) {
        String state = exception.getSQLState();
        return state != null && state.startsWith("23");
    }
}
