package com.clara.dto.memory;

import lombok.Data;

/**
 * 窗口策略：会话历史允许携带的上限，支持条数、具体大小、百分比三种维度。
 *
 * <p>组合时取 AND（更严）；默认按百分比 80% 限定、不限制条数。{@code -1} 表示「不限」，{@code 0} 表示
 * 「不启用该维度」。
 */
@Data
public final class WindowPolicy {

    private final long contextWindowTokens;
    private final int maxMessages;
    private final long maxSizeBytes;
    private final int maxTokensPercent;
    /**
     * 最近 N 轮对话至少保留在历史中；0 表示不启用该下限。
     *
     * <p>一轮从一条用户消息开始，包含其后的助手回复与工具往返。该下限优先于条数 / 字节数 / 百分比限制：
     * 即使窗口已经超限，也不会把最近 N 轮裁掉。
     */
    private final int recentTurnsKept;

    /**
     * 创建窗口策略（不设置最近轮次下限，等价于 {@code recentTurnsKept = 0}）。
     *
     * @param contextWindowTokens 平台上下文窗口 token 上限，-1 表示未知
     * @param maxMessages         历史最大条数，-1 表示不限
     * @param maxSizeBytes        历史最大字节数，-1 表示不限
     * @param maxTokensPercent    历史占上下文窗口最大百分比（0-100），0 表示不按百分比
     */
    public WindowPolicy(long contextWindowTokens, int maxMessages, long maxSizeBytes, int maxTokensPercent) {
        this(contextWindowTokens, maxMessages, maxSizeBytes, maxTokensPercent, 0);
    }

    /**
     * 创建窗口策略。
     *
     * @param contextWindowTokens 平台上下文窗口 token 上限，-1 表示未知
     * @param maxMessages         历史最大条数，-1 表示不限
     * @param maxSizeBytes        历史最大字节数，-1 表示不限
     * @param maxTokensPercent    历史占上下文窗口最大百分比（0-100），0 表示不按百分比
     * @param recentTurnsKept     最近至少保留的对话轮数，0 表示不启用
     * @throws IllegalArgumentException 任意字段不合法时
     */
    public WindowPolicy(long contextWindowTokens, int maxMessages, long maxSizeBytes, int maxTokensPercent,
            int recentTurnsKept) {
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
        if (recentTurnsKept < 0) {
            throw new IllegalArgumentException("recentTurnsKept must not be negative");
        }
        this.contextWindowTokens = contextWindowTokens;
        this.maxMessages = maxMessages;
        this.maxSizeBytes = maxSizeBytes;
        this.maxTokensPercent = maxTokensPercent;
        this.recentTurnsKept = recentTurnsKept;
    }

    /**
     * 默认策略：按百分比 80% 限定、不限制条数与大小，且无上下文窗口 token 基数。
     *
     * @return 默认窗口策略，非 null
     */
    public static WindowPolicy defaults() {
        return new WindowPolicy(-1L, -1, -1L, 80, 10);
    }
}
