package com.clara.core.memory;

/**
 * 嵌入接口：把文本向量化，通过云端嵌入 API 完成。
 *
 * <p>core 定义的契约接口，由 clara-model 侧（或宿主装配）提供实现；memory 只负责存储与检索。
 */
public interface IEmbeddingClient {

    /**
     * 把单段文本向量化。
     *
     * @param text 待向量化文本，不可为 null
     * @return 向量，非 null 且长度大于 0
     */
    float[] embed(String text);
}
