package com.clara.memory.semantic.milvus;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Milvus 后端工厂：按连接配置创建 {@link MilvusBackend}。
 *
 * <p>工厂只负责参数装配与客户端构造；SDK 在构造客户端时就会建立 gRPC 连接并阻塞到连接超时，
 * 因此 Milvus 不可达会在创建阶段直接抛异常，调用方应按外部服务错误（可重试）处理。
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
        return create(config, spec, MilvusBackendFactory::bm25Backend, MilvusBackendFactory::grpcBackend);
    }

    /**
     * 按集合规格选择后端，并把「选哪种后端」与「怎么建客户端」解耦。
     *
     * <p>Milvus SDK 客户端在构造阶段就会发起 gRPC 握手，没有实例的环境里会一直等到连接超时，
     * 因此单元测试无法直接调用 {@link #create(MilvusBackendConfig, MilvusCollectionSpec)} 验证选型。
     * 这里把两种后端的构造方式作为参数注入：生产调用传真实构造方法，单元测试传桩函数，
     * 只钉死选型规则，真实客户端的联测由集成测试在 Milvus 可达时执行。
     *
     * @param config      连接配置，不可为 null
     * @param spec        集合规格（决定后端类型），可为 null（等于向量模式）
     * @param bm25Factory bm25 模式的后端构造器，不可为 null
     * @param grpcFactory 向量模式的后端构造器，不可为 null
     * @return 由对应构造器创建的后端，非 null
     * @throws IllegalArgumentException config 为 null 时
     */
    static MilvusBackend create(MilvusBackendConfig config, MilvusCollectionSpec spec,
            Function<MilvusBackendConfig, MilvusBackend> bm25Factory,
            Function<MilvusBackendConfig, MilvusBackend> grpcFactory) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (spec != null && spec.isBm25()) {
            return bm25Factory.apply(config);
        }
        return grpcFactory.apply(config);
    }

    private static MilvusBackend bm25Backend(MilvusBackendConfig config) {
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

    private static MilvusBackend grpcBackend(MilvusBackendConfig config) {
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
