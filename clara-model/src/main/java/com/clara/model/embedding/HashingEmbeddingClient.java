package com.clara.model.embedding;

import com.clara.core.memory.IEmbeddingClient;

import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * 占位嵌入客户端：不访问网络，用文本哈希确定性地生成单位向量。
 *
 * <p>是什么、在哪一层：{@code clara-model} 的 {@link IEmbeddingClient} 占位实现（{@code com.clara.model.embedding}），
 * 用于离线开发 / 单元测试 / 无真实嵌入凭据的环境——它保证「同一文本得到同一向量」，可让语义记忆的本地链路
 * 先跑通，待接入真实凭据后替换为 {@link HttpEmbeddingClient}。向量不含语义，仅占位，不能用于生产检索。
 *
 * <p>生命周期与线程安全：实例只持有一个只读维度常量，线程安全、可跨线程共享。
 */
public final class HashingEmbeddingClient implements IEmbeddingClient {

    /**
     * 默认向量维度。
     */
    private static final int DEFAULT_DIMENSION = 1024;

    /**
     * 向量维度。
     */
    private final int dimension;

    /**
     * 使用默认维度（1024）创建占位嵌入客户端。
     */
    public HashingEmbeddingClient() {
        this(DEFAULT_DIMENSION);
    }

    /**
     * 使用指定维度创建占位嵌入客户端。
     *
     * @param dimension 向量维度，必须为正
     * @throws IllegalArgumentException dimension 非正时
     */
    public HashingEmbeddingClient(int dimension) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension must be positive");
        }
        this.dimension = dimension;
    }

    /**
     * 用文本哈希确定性地生成一个 L2 归一化的单位向量。
     *
     * @param text 待向量化文本，不可为 null
     * @return 长度为 {@code dimension} 的单位向量，非 null
     */
    @Override
    public float[] embed(String text) {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        Random random = new Random(fnv1a64(text));
        float[] vector = new float[dimension];
        double squareSum = 0.0;
        for (int i = 0; i < dimension; i++) {
            float value = random.nextFloat() * 2.0f - 1.0f;
            vector[i] = value;
            squareSum += value * value;
        }
        double length = Math.sqrt(squareSum);
        for (int i = 0; i < dimension; i++) {
            vector[i] = (float) (vector[i] / length);
        }
        return vector;
    }

    /**
     * 计算文本 UTF-8 字节的 FNV-1a 64 位哈希，作为确定性伪随机种子。
     *
     * @param text 待哈希文本，非 null
     * @return 64 位哈希
     */
    private static long fnv1a64(String text) {
        long hash = 0xcbf29ce484222325L;
        for (byte value : text.getBytes(StandardCharsets.UTF_8)) {
            hash ^= value & 0xffL;
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
