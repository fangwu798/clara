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
import java.util.regex.Pattern;

/**
 * JDBC 会话存储：通过 JDBC 持久化会话快照，用于多实例共享部署。
 *
 * <p>以 {@code store_key} 为主键的简单单表实现；{@code append} 用
 * {@code UPDATE ... WHERE version = expectedVersion} 做乐观并发，未命中且行不存在时插入。
 * 需要调用方注入 {@link DataSource} 并保证目标表已存在。
 */
public final class JdbcConversationStore implements ConversationStore {

    private static final String WHERE_STORE_KEY = " WHERE store_key = ?";

    /** 表名白名单：只允许字母或下划线开头的简单标识符，杜绝 SQL 注入。 */
    private static final Pattern SIMPLE_IDENTIFIER = Pattern.compile("[A-Za-z_]\\w*");

    /**
     * 默认表名。
     */
    public static final String DEFAULT_TABLE = "clara_conversation";

    private final DataSource dataSource;
    private final ConversationCodec codec = new ConversationCodec();

    /** 读取快照语句，表名在构造期校验后一次性拼接。 */
    private final String selectSql;

    /** 乐观并发更新语句，表名在构造期校验后一次性拼接。 */
    private final String updateSql;

    /** 首次写入语句，表名在构造期校验后一次性拼接。 */
    private final String insertSql;

    /** 删除语句，表名在构造期校验后一次性拼接。 */
    private final String deleteSql;

    /** 存在性探测语句，表名在构造期校验后一次性拼接。 */
    private final String existsSql;

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
        String tableName = requireSimpleIdentifier(table);
        this.dataSource = dataSource;
        this.selectSql = "SELECT payload FROM " + tableName + WHERE_STORE_KEY;
        this.updateSql = "UPDATE " + tableName + " SET version = ?, payload = ? WHERE store_key = ? AND version = ?";
        this.insertSql = "INSERT INTO " + tableName + " (store_key, version, payload) VALUES (?, ?, ?)";
        this.deleteSql = "DELETE FROM " + tableName + WHERE_STORE_KEY;
        this.existsSql = "SELECT 1 FROM " + tableName + WHERE_STORE_KEY;
    }

    @Override
    public ConversationSnapshot loadSnapshot(ConversationKey key) {
        open();
        requireKey(key);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(selectSql)) {
            statement.setString(1, key.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return new ConversationSnapshot(0L, Collections.emptyList());
                }
                byte[] payload = resultSet.getBytes(1);
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
            persist(connection, key, next, payload, expectedVersion);
        } catch (SQLException e) {
            throw ConversationStoreErrors.io();
        }
    }

    /**
     * 在单个连接内完成乐观并发更新或插入，并负责事务提交与回滚。
     */
    private void persist(Connection connection, ConversationKey key, ConversationSnapshot next,
            byte[] payload, long expectedVersion) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            int updated;
            try (PreparedStatement update = connection.prepareStatement(updateSql)) {
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
                try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
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
    }

    @Override
    public void clear(ConversationKey key) {
        open();
        requireKey(key);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(deleteSql)) {
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
        try (PreparedStatement statement = connection.prepareStatement(existsSql)) {
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

    /**
     * 校验表名，仅接受 {@code [A-Za-z_]\w*} 形式的简单标识符。
     *
     * <p>表名无法通过占位符绑定，因此在参与任何 SQL 拼接前必须先做白名单校验；
     * 校验通过的值只在构造期参与一次拼接，运行期执行的始终是固定语句。
     *
     * @param table 待校验的表名
     * @return 校验通过的表名
     * @throws IllegalArgumentException 表名为 null 或不满足标识符规则
     */
    private static String requireSimpleIdentifier(String table) {
        if (table == null || !SIMPLE_IDENTIFIER.matcher(table).matches()) {
            throw new IllegalArgumentException("table must be a simple identifier");
        }
        return table;
    }
}
