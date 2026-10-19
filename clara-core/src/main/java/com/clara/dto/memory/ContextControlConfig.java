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
     * 通过构建器创建上下文控制配置，并完成字段校验。
     *
     * @param builder 已填充的构建器，不能为 null
     * @throws IllegalArgumentException 任意字段超出允许范围时
     */
    private ContextControlConfig(Builder builder) {
        if (builder.contextWindowTokens == 0 || builder.contextWindowTokens < -1) {
            throw new IllegalArgumentException("contextWindowTokens must be -1 or positive");
        }
        if (builder.windowMaxMessages == 0 || builder.windowMaxMessages < -1) {
            throw new IllegalArgumentException("windowMaxMessages must be -1 or positive");
        }
        if (builder.windowMaxSizeBytes < -1) {
            throw new IllegalArgumentException("windowMaxSizeBytes must be -1 or non-negative");
        }
        if (builder.windowMaxTokensPercent < 0 || builder.windowMaxTokensPercent > 100) {
            throw new IllegalArgumentException("windowMaxTokensPercent must be within [0, 100]");
        }
        if (builder.retrievalCount < 0) {
            throw new IllegalArgumentException("retrievalCount must not be negative");
        }
        if (Double.isNaN(builder.similarityThreshold)
                || builder.similarityThreshold < 0.0 || builder.similarityThreshold > 1.0) {
            throw new IllegalArgumentException("similarityThreshold must be within [0, 1]");
        }
        if (builder.toolOutputMaxChars == 0 || builder.toolOutputMaxChars < -1) {
            throw new IllegalArgumentException("toolOutputMaxChars must be -1 or positive");
        }
        this.contextWindowTokens = builder.contextWindowTokens;
        this.windowMaxMessages = builder.windowMaxMessages;
        this.windowMaxSizeBytes = builder.windowMaxSizeBytes;
        this.windowMaxTokensPercent = builder.windowMaxTokensPercent;
        this.compressEnabled = builder.compressEnabled;
        this.vectorizeEnabled = builder.vectorizeEnabled;
        this.retrievalCount = builder.retrievalCount;
        this.similarityThreshold = builder.similarityThreshold;
        this.toolOutputMaxChars = builder.toolOutputMaxChars;
    }

    /**
     * 创建上下文控制配置构建器。
     *
     * @return 构建器，非 null
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 默认配置：按百分比 80% 限定上下文窗口，不启用压缩与向量化，检索 3 条，相似度阈值 0.7。
     *
     * @return 默认上下文控制配置，非 null
     */
    public static ContextControlConfig defaults() {
        return builder()
                .contextWindowTokens(-1L)
                .windowMaxMessages(-1)
                .windowMaxSizeBytes(-1L)
                .windowMaxTokensPercent(80)
                .retrievalCount(3)
                .similarityThreshold(0.7)
                .build();
    }

    /**
     * 上下文控制配置构建器；数值字段未设置时为 0，工具输出截断默认 4000 字符。
     */
    public static final class Builder {

        private long contextWindowTokens;
        private int windowMaxMessages;
        private long windowMaxSizeBytes;
        private int windowMaxTokensPercent;
        private boolean compressEnabled;
        private boolean vectorizeEnabled;
        private int retrievalCount;
        private double similarityThreshold;
        private int toolOutputMaxChars = DEFAULT_TOOL_OUTPUT_MAX_CHARS;

        private Builder() {
        }

        public Builder contextWindowTokens(long contextWindowTokens) {
            this.contextWindowTokens = contextWindowTokens;
            return this;
        }

        public Builder windowMaxMessages(int windowMaxMessages) {
            this.windowMaxMessages = windowMaxMessages;
            return this;
        }

        public Builder windowMaxSizeBytes(long windowMaxSizeBytes) {
            this.windowMaxSizeBytes = windowMaxSizeBytes;
            return this;
        }

        public Builder windowMaxTokensPercent(int windowMaxTokensPercent) {
            this.windowMaxTokensPercent = windowMaxTokensPercent;
            return this;
        }

        public Builder compressEnabled(boolean compressEnabled) {
            this.compressEnabled = compressEnabled;
            return this;
        }

        public Builder vectorizeEnabled(boolean vectorizeEnabled) {
            this.vectorizeEnabled = vectorizeEnabled;
            return this;
        }

        public Builder retrievalCount(int retrievalCount) {
            this.retrievalCount = retrievalCount;
            return this;
        }

        public Builder similarityThreshold(double similarityThreshold) {
            this.similarityThreshold = similarityThreshold;
            return this;
        }

        public Builder toolOutputMaxChars(int toolOutputMaxChars) {
            this.toolOutputMaxChars = toolOutputMaxChars;
            return this;
        }

        public ContextControlConfig build() {
            return new ContextControlConfig(this);
        }
    }
}
