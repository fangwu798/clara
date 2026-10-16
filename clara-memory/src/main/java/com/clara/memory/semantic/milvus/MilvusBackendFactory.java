package com.clara.memory.semantic.milvus;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;

import java.util.concurrent.TimeUnit;

/**
 * Milvus 后端工厂：按连接配置创建 {@link MilvusBackend}。
 *
 * <p>连接失败在首次发起 RPC 时体现为 {@link MilvusBackendException}（外部服务错误，可重试）；工厂本身
 * 只做参数装配与客户端构造，不发起网络调用。
 *
 * <p>后端选型由集合规格的检索模式决定：{@code bm25} 走全文检索后端（v2 客户端，需要服务端
 * 支持全文检索与分词器，Milvus 2.5+），其余走向量后端。两者用的是不同的 SDK 客户端类型，
 * 不能互换。
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
        return create(config, null);
    }

    /**
     * 按配置与集合规格创建 Milvus 后端。
     *
     * @param config 连接配置，不可为 null
     * @param spec   集合规格（决定后端类型），可为 null（等于向量模式）
     * @return 已建立客户端的后端，非 null
     * @throws IllegalArgumentException config 为 null 时
     */
    public static MilvusBackend create(MilvusBackendConfig config, MilvusCollectionSpec spec) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (spec != null && spec.isBm25()) {
            ConnectConfig.ConnectConfigBuilder bm25 = ConnectConfig.builder()
                    .uri("http://" + config.getHost() + ":" + config.getPort())
                    .dbName(config.getDatabase())
                    .connectTimeoutMs(config.getConnectTimeoutMillis());
            if (config.getUsername() != null && !config.getUsername().isEmpty()) {
                bm25.username(config.getUsername());
                bm25.password(config.getPassword() == null ? "" : config.getPassword());
            }
            return new MilvusBm25Backend(new MilvusClientV2(bm25.build()));
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
