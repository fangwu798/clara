package com.clara.dto.memory;

import lombok.Data;

/**
 * 上下文历史处理控制配置：承载窗口限制、压缩、向量化与检索的完整设置。
 *
 * <p>来自 clara-model 的 {@code control} 节点，与模型类型同级别；窗口三维度沿用 {@link WindowPolicy} 的口径
 * （{@code -1} 表示不限 / 未知，{@code 0} 表示不启用百分比维度）。组合时取 AND（更严）。
 */
@Data
public final class ContextControlConfig {

    private final long contextWindowTokens;
    private final int windowMaxMessages;
    private final long windowMaxSizeBytes;
    private final int windowMaxTokensPercent;
    private final boolean compressEnabled;
    private final boolean vectorizeEnabled;
    private final int retrievalCount;
    private final double similarityThreshold;
    /**
     * 历史工具结果单条最大保留字符数，-1 表示不限。
     *
     * <p>只作用于「当前提问之前」的历史工具结果：本轮（最后一条用户消息之后）的工具结果原样保留，
     * 供模型继续推理；历史工具结果超出该长度时保留首尾、中间以省略标记替代。用户提问与助手回复不压缩。
     */
    private final int toolOutputMaxChars;

    /**
     * 历史工具结果单条默认最大保留字符数。
     */
    public static final int DEFAULT_TOOL_OUTPUT_MAX_CHARS = 4000;

    /**
     * 创建上下文控制配置（历史工具结果压缩取默认值）。
     *
     * @param contextWindowTokens   平台上下文窗口 token 上限，-1 表示未知
     * @param windowMaxMessages     历史最大条数，-1 表示不限
     * @param windowMaxSizeBytes    历史最大字节数，-1 表示不限
     * @param windowMaxTokensPercent 历史占上下文窗口最大百分比（0-100），0 表示不按百分比
     * @param compressEnabled       是否启用超限压缩
     * @param vectorizeEnabled      是否启用向量化入库
     * @param retrievalCount        每次检索返回的知识条数，须非负，0 表示不检索
     * @param similarityThreshold   检索相似度阈值，须在 {@code [0, 1]} 区间
     * @throws IllegalArgumentException 任一字段不合法时
     */
    public ContextControlConfig(long contextWindowTokens, int windowMaxMessages, long windowMaxSizeBytes,
            int windowMaxTokensPercent, boolean compressEnabled, boolean vectorizeEnabled,
            int retrievalCount, double similarityThreshold) {
        this(contextWindowTokens, windowMaxMessages, windowMaxSizeBytes, windowMaxTokensPercent,
                compressEnabled, vectorizeEnabled, retrievalCount, similarityThreshold,
                DEFAULT_TOOL_OUTPUT_MAX_CHARS);
    }

    /**
     * 创建上下文控制配置。
     *
     * @param contextWindowTokens   平台上下文窗口 token 上限，-1 表示未知
     * @param windowMaxMessages     历史最大条数，-1 表示不限
     * @param windowMaxSizeBytes    历史最大字节数，-1 表示不限
     * @param windowMaxTokensPercent 历史占上下文窗口最大百分比（0-100），0 表示不按百分比
     * @param compressEnabled       是否启用超限压缩
     * @param vectorizeEnabled      是否启用向量化入库
     * @param retrievalCount        每次检索返回的知识条数，须非负，0 表示不检索
     * @param similarityThreshold   检索相似度阈值，须在 {@code [0, 1]} 区间
     * @param toolOutputMaxChars    历史工具结果单条最大保留字符数，-1 表示不限
     * @throws IllegalArgumentException 任一字段不合法时
     */
    public ContextControlConfig(long contextWindowTokens, int windowMaxMessages, long windowMaxSizeBytes,
            int windowMaxTokensPercent, boolean compressEnabled, boolean vectorizeEnabled,
            int retrievalCount, double similarityThreshold, int toolOutputMaxChars) {
        if (contextWindowTokens == 0 || contextWindowTokens < -1) {
            throw new IllegalArgumentException("contextWindowTokens must be -1 or positive");
        }
        if (windowMaxMessages == 0 || windowMaxMessages < -1) {
            throw new IllegalArgumentException("windowMaxMessages must be -1 or positive");
        }
        if (windowMaxSizeBytes < -1) {
            throw new IllegalArgumentException("windowMaxSizeBytes must be -1 or non-negative");
        }
        if (windowMaxTokensPercent < 0 || windowMaxTokensPercent > 100) {
            throw new IllegalArgumentException("windowMaxTokensPercent must be within [0, 100]");
        }
        if (retrievalCount < 0) {
            throw new IllegalArgumentException("retrievalCount must not be negative");
        }
        if (Double.isNaN(similarityThreshold) || similarityThreshold < 0.0 || similarityThreshold > 1.0) {
            throw new IllegalArgumentException("similarityThreshold must be within [0, 1]");
        }
        if (toolOutputMaxChars == 0 || toolOutputMaxChars < -1) {
            throw new IllegalArgumentException("toolOutputMaxChars must be -1 or positive");
        }
        this.contextWindowTokens = contextWindowTokens;
        this.windowMaxMessages = windowMaxMessages;
        this.windowMaxSizeBytes = windowMaxSizeBytes;
        this.windowMaxTokensPercent = windowMaxTokensPercent;
        this.compressEnabled = compressEnabled;
        this.vectorizeEnabled = vectorizeEnabled;
        this.retrievalCount = retrievalCount;
        this.similarityThreshold = similarityThreshold;
        this.toolOutputMaxChars = toolOutputMaxChars;
    }

    /**
     * 默认配置：按百分比 80% 限定、不限制条数与大小、关闭压缩与向量化、检索 3 条、阈值 0.7。
     *
     * @return 默认上下文控制配置，非 null
     */
    public static ContextControlConfig defaults() {
        return new ContextControlConfig(-1L, -1, -1L, 80, false, false, 3, 0.7);
    }
}
