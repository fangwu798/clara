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
    /**
     * 最近至少保留在历史中的对话轮数；默认 10，0 表示不启用。
     *
     * <p>优先于条数 / 字节数 / 百分比限制：最近 N 轮不会被窗口裁掉。
     */
    private final int recentTurnsKept;
    private final boolean compressEnabled;
    private final boolean vectorizeEnabled;
    private final int retrievalCount;
    private final double similarityThreshold;
    /**
     * 工具结果单条最大保留字符数（压缩后的上限），-1 表示不限。
     *
     * <p>工具结果超出该长度时保留首尾、中间以省略标记替代；用户提问与助手回复不压缩。
     * 该值同时决定单次请求的工具结果总预算（见 {@link #getToolOutputBudgetChars()}）。
     */
    private final int toolOutputMaxChars;

    /**
     * 工具结果单条默认最大保留字符数。
     */
    public static final int DEFAULT_TOOL_OUTPUT_MAX_CHARS = 4000;

    /**
     * 单次请求工具结果总预算 = 单条上限 × 该倍数。
     *
     * <p>为什么用倍数而不是再暴露一个配置项：单条上限描述「一条结果留多少」，总量预算描述「一次请求
     * 最多带多少工具输出」。两者按固定倍数绑定，既能约束总体积，又不会让配置项继续膨胀；默认
     * 4000 × 10 = 40000 字符，约等于 10 条满额结果。
     */
    public static final int TOOL_OUTPUT_BUDGET_MULTIPLIER = 10;

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
        if (builder.recentTurnsKept < 0) {
            throw new IllegalArgumentException("recentTurnsKept must not be negative");
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
        this.recentTurnsKept = builder.recentTurnsKept;
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
                .recentTurnsKept(10)
                .retrievalCount(3)
                .similarityThreshold(0.7)
                .build();
    }

    /**
     * 单次请求允许携带的工具结果总字符预算。
     *
     * <p>{@code toolOutputMaxChars <= 0}（不压缩）时返回 -1，表示不限制总量。
     *
     * @return 总字符预算；-1 表示不限
     */
    public int getToolOutputBudgetChars() {
        if (toolOutputMaxChars <= 0) {
            return -1;
        }
        long budget = (long) toolOutputMaxChars * TOOL_OUTPUT_BUDGET_MULTIPLIER;
        return budget > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) budget;
    }

    /**
     * 上下文控制配置构建器；数值字段未设置时为 0，工具输出截断默认 4000 字符。
     */
    public static final class Builder {

        private long contextWindowTokens;
        private int windowMaxMessages;
        private long windowMaxSizeBytes;
        private int windowMaxTokensPercent;
        private int recentTurnsKept = 10;
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

        public Builder recentTurnsKept(int recentTurnsKept) {
            this.recentTurnsKept = recentTurnsKept;
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
