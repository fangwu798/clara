package com.clara.memory.semantic.milvus;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;

import java.util.concurrent.TimeUnit;

/**
 * Milvus 后端工厂：按连接配置创建 {@link MilvusBackend}。
 *
 * <p>连接失败在首次发起 RPC 时体现为 {@link MilvusBackendException}（外部服务错误，可重试）；工厂本身
 * 只做参数装配与客户端构造，不发起网络调用。
 */
public final class MilvusBackendFactory {

    private MilvusBackendFactory() {
    }

    /**
     * 按配置创建 Milvus 后端。
     *
     * @param config 连接配置，不可为 null
     * @return 已建立客户端的后端，非 null
     * @throws IllegalArgumentException config 为 null 时
     */
    public static MilvusBackend create(MilvusBackendConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        ConnectParam.Builder builder = ConnectParam.newBuilder()
                .withHost(config.getHost())
                .withPort(config.getPort())
                .withDatabaseName(config.getDatabase())
                .withConnectTimeout(config.getConnectTimeoutMillis(), TimeUnit.MILLISECONDS)
                .withIdleTimeout(config.getIdleTimeoutMillis(), TimeUnit.MILLISECONDS);
        if (config.getUsername() != null && !config.getUsername().isEmpty()) {
            builder.withAuthorization(config.getUsername(),
                    config.getPassword() == null ? "" : config.getPassword());
        }
        MilvusServiceClient client = new MilvusServiceClient(builder.build());
        return new MilvusGrpcBackend(client);
    }
}
