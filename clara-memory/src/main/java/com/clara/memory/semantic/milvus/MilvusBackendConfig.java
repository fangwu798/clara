package com.clara.memory.semantic.milvus;

/**
 * Milvus 后端连接配置：主机、端口、可选鉴权、数据库名与超时。
 *
 * <p>仅承载连接参数，不持有 SDK 客户端；由 {@link MilvusBackendFactory#create(MilvusBackendConfig)}
 * 消费后建立连接。
 */
public final class MilvusBackendConfig {

    /**
     * 默认数据库名（Milvus 内置默认库）。
     */
    public static final String DEFAULT_DATABASE = "default";

    /**
     * 默认连接超时毫秒数。
     */
    public static final long DEFAULT_CONNECT_TIMEOUT_MILLIS = 10000L;

    /**
     * 默认空闲超时毫秒数。
     */
    public static final long DEFAULT_IDLE_TIMEOUT_MILLIS = 60000L;

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String database;
    private final long connectTimeoutMillis;
    private final long idleTimeoutMillis;

    /**
     * 创建无鉴权的默认配置。
     *
     * @param host 主机名，不可为空白
     * @param port 端口，必须为正
     */
    public MilvusBackendConfig(String host, int port) {
        this(host, port, null, null, DEFAULT_DATABASE, DEFAULT_CONNECT_TIMEOUT_MILLIS, DEFAULT_IDLE_TIMEOUT_MILLIS);
    }

    /**
     * 创建完整连接配置。
     *
     * @param host                 主机名，不可为空白
     * @param port                 端口，必须为正
     * @param username             鉴权用户名，可为 null（无鉴权）
     * @param password             鉴权密码，可为 null
     * @param database             数据库名，不可为空白
     * @param connectTimeoutMillis 连接超时毫秒数，必须为正
     * @param idleTimeoutMillis    空闲超时毫秒数，必须为正
     * @throws IllegalArgumentException 任一字段不合法时
     */
    public MilvusBackendConfig(String host, int port, String username, String password, String database,
            long connectTimeoutMillis, long idleTimeoutMillis) {
        if (blank(host) || port <= 0 || blank(database) || connectTimeoutMillis <= 0 || idleTimeoutMillis <= 0) {
            throw new IllegalArgumentException("milvus backend config is invalid");
        }
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.database = database;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.idleTimeoutMillis = idleTimeoutMillis;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getDatabase() {
        return database;
    }

    public long getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public long getIdleTimeoutMillis() {
        return idleTimeoutMillis;
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
