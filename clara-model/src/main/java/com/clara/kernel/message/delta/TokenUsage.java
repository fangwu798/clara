package com.clara.kernel.message.delta;

import lombok.Data;

/**
 * 表示一次模型调用返回的 token 用量。
 *
 * <p>用途：承载响应（{@code ChatResponse}）与流事件中的用量信息，用于计费、限流与可观测性。
 * 各平台的字段名不同，解析由对应的 provider 编解码器与流累加器负责，本类只做统一承载：
 * <ul>
 *   <li>{@link #getInputTokens()} / {@link #getOutputTokens()}：输入提示与生成内容的 token 数；</li>
 *   <li>{@link #getTotalTokens()}：总量，平台显式返回则以其为准，否则按输入加输出计算；</li>
 *   <li>{@link #getCacheTokens()}：命中的缓存 token 数，null 表示平台未提供，而不是 0。</li>
 * </ul>
 *
 * <p>注意：缓存命中的 token 通常已经包含在输入 token 中，统计总量时不要重复累加；本类不做
 * 「总量等于输入加输出」的一致性校验，因为部分平台会因缓存折扣给出不同的总量口径。
 */
@Data
public final class TokenUsage {
    private final int inputTokens;
    private final int outputTokens;
    private final Integer totalTokens;
    private final Integer cacheTokens;

    /**
     * 创建 token 用量。
     *
     * @param inputTokens  输入 token 数，不可为负
     * @param outputTokens 输出 token 数，不可为负
     * @param totalTokens  平台返回的总 token 数，可为 null（表示平台未提供）
     * @param cacheTokens  命中的缓存 token 数，可为 null（表示平台未提供）
     * @throws IllegalArgumentException 任一非 null 的计数为负时
     */
    public TokenUsage(int inputTokens, int outputTokens, Integer totalTokens, Integer cacheTokens) {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
        if (totalTokens != null && totalTokens.intValue() < 0) {
            throw new IllegalArgumentException("total tokens must not be negative");
        }
        if (cacheTokens != null && cacheTokens.intValue() < 0) {
            throw new IllegalArgumentException("cache tokens must not be negative");
        }
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.totalTokens = totalTokens;
        this.cacheTokens = cacheTokens;
    }
}
