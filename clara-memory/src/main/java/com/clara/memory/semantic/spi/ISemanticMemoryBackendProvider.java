package com.clara.memory.semantic.spi;

import java.nio.file.Path;

/**
 * 可选语义记忆后端 SPI。
 *
 * <p>主库只内置进程内实现；Milvus 等外部后端放在独立模块中，通过 {@link java.util.ServiceLoader}
 * 注册。装配方遍历所有实现，第一个成功返回后端的提供者生效，其余忽略。
 *
 * <p>实现必须无状态或可安全复用：未配置或连接失败时返回 {@code null}，由装配方回退到本地实现，
 * 不允许抛出异常阻断启动。
 */
public interface ISemanticMemoryBackendProvider {

    /**
     * 按配置尝试打开后端。
     *
     * @param claraConfig      {@code clara.yml} 路径，可能为 {@code null}
     * @param defaultDimension 未显式配置时使用的向量维度
     * @param projectName      项目名，用于隔离集合；可能为 {@code null}
     * @return 后端实例与描述；未配置或不可用时返回 {@code null}
     */
    SemanticMemoryBackend open(Path claraConfig, int defaultDimension, String projectName);
}
