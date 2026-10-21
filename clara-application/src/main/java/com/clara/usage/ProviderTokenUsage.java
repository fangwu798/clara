package com.clara.usage;

/**
 * 单个平台在一次进程 / 会话内的累计 token 用量。
 *
 * <p>不可变值对象：每次累计都返回新实例，便于 {@code InMemoryCallRecordSink} 在同步块内替换快照，
 * 读取方拿到的对象不会被后续调用修改。
 *
 * <p>口径：{@code calls} 为模型调用次数；{@code totalTokens} 优先取平台显式返回值，平台未返回时按
 * 输入 + 输出推导。
 */
public final class ProviderTokenUsage {

    /**
     * 全零用量，作为累计初值。
     */
    public static final ProviderTokenUsage EMPTY = new ProviderTokenUsage(0L, 0L, 0L, 0L);

    /** 调用次数。 */
    private final long calls;
    /** 输入 token 数。 */
    private final long inputTokens;
    /** 输出 token 数。 */
    private final long outputTokens;
    /** 总 token 数。 */
    private final long totalTokens;

    /**
     * 创建用量快照。
     *
     * @param calls        调用次数，负数按 0 处理
     * @param inputTokens  输入 token，负数按 0 处理
     * @param outputTokens 输出 token，负数按 0 处理
     * @param totalTokens  总 token，负数按 0 处理
     */
    public ProviderTokenUsage(long calls, long inputTokens, long outputTokens, long totalTokens) {
        this.calls = Math.max(0L, calls);
        this.inputTokens = Math.max(0L, inputTokens);
        this.outputTokens = Math.max(0L, outputTokens);
        this.totalTokens = Math.max(0L, totalTokens);
    }

    /**
     * 累加另一份用量。
     *
     * @param other 另一份用量，可为 null（返回当前对象）
     * @return 累加后的新快照，非 null
     */
    public ProviderTokenUsage plus(ProviderTokenUsage other) {
        if (other == null) {
            return this;
        }
        return new ProviderTokenUsage(calls + other.calls, inputTokens + other.inputTokens,
                outputTokens + other.outputTokens, totalTokens + other.totalTokens);
    }

    /**
     * 获取调用次数。
     *
     * @return 调用次数
     */
    public long getCalls() {
        return calls;
    }

    /**
     * 获取输入 token 数。
     *
     * @return 输入 token 数
     */
    public long getInputTokens() {
        return inputTokens;
    }

    /**
     * 获取输出 token 数。
     *
     * @return 输出 token 数
     */
    public long getOutputTokens() {
        return outputTokens;
    }

    /**
     * 获取总 token 数。
     *
     * @return 总 token 数
     */
    public long getTotalTokens() {
        return totalTokens;
    }
}