package com.clara.dto.memory;

/**
 * 窗口策略：会话历史允许携带的上限，支持条数、具体大小、百分比三种维度。
 *
 * <p>组合时取 AND（更严）；默认按百分比 80% 限定、不限制条数。{@code -1} 表示「不限」，{@code 0} 表示
 * 「不启用该维度」。
 */
public final class WindowPolicy {

    private final long contextWindowTokens;
    private final int maxMessages;
    private final long maxSizeBytes;
    private final int maxTokensPercent;

    /**
     * 创建窗口策略。
     *
     * @param contextWindowTokens 平台上下文窗口 token 上限，-1 表示未知
     * @param maxMessages         历史最大条数，-1 表示不限
     * @param maxSizeBytes        历史最大字节数，-1 表示不限
     * @param maxTokensPercent    历史占上下文窗口最大百分比（0-100），0 表示不按百分比
     * @throws IllegalArgumentException 任一字段不合法时
     */
    public WindowPolicy(long contextWindowTokens, int maxMessages, long maxSizeBytes, int maxTokensPercent) {
        if (contextWindowTokens == 0 || contextWindowTokens < -1) {
            throw new IllegalArgumentException("contextWindowTokens must be -1 or positive");
        }
        if (maxMessages == 0 || maxMessages < -1) {
            throw new IllegalArgumentException("maxMessages must be -1 or positive");
        }
        if (maxSizeBytes < -1) {
            throw new IllegalArgumentException("maxSizeBytes must be -1 or non-negative");
        }
        if (maxTokensPercent < 0 || maxTokensPercent > 100) {
            throw new IllegalArgumentException("maxTokensPercent must be within [0, 100]");
        }
        this.contextWindowTokens = contextWindowTokens;
        this.maxMessages = maxMessages;
        this.maxSizeBytes = maxSizeBytes;
        this.maxTokensPercent = maxTokensPercent;
    }

    /**
     * 默认策略：按百分比 80% 限定、不限制条数与大小，且无上下文窗口 token 基数。
     *
     * @return 默认窗口策略，非 null
     */
    public static WindowPolicy defaults() {
        return new WindowPolicy(-1L, -1, -1L, 80);
    }

    public long getContextWindowTokens() {
        return contextWindowTokens;
    }

    public int getMaxMessages() {
        return maxMessages;
    }

    public long getMaxSizeBytes() {
        return maxSizeBytes;
    }

    public int getMaxTokensPercent() {
        return maxTokensPercent;
    }
}
