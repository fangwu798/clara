package com.clara.kernel.config;

import com.clara.dto.memory.ContextControlConfig;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

/**
 * 上下文历史处理控制配置：承载窗口限制、压缩、向量化与检索的完整设置。
 *
 * <p>是什么、在哪一层：配置层（{@code com.clara.kernel.config}）的不可变值对象，作为
 * {@link ProviderCapabilityConfiguration} 的 {@code control} 节点，与模型类型（{@code modelId}）同级别——
 * 同一平台不同模型可各自配置不同的控制参数，区别于 {@code parameters}（下发给大模型的调用参数覆盖）。
 *
 * <p>YAML 形状为 {@code capabilities.<能力种类>.control}，键名与 {@link ContextControlConfig} 字段一一对应；
 * 每个字段都可省略，省略时按默认值处理（默认值与 {@link ContextControlConfig#defaults()} 一致）：
 * 窗口按百分比 80% 限定、不限制条数与大小、关闭压缩与向量化、检索 3 条、相似度阈值 0.7。
 *
 * <p>生命周期与线程安全：字段全部 final、无连接无缓冲，实例可跨线程共享。
 *
 * <p>失败语义：任一字段取值越界时构造期抛 {@link IllegalArgumentException}，配置问题在加载期暴露。
 */
@Getter
public final class ContextControlConfiguration {

    /**
     * 平台上下文窗口 token 上限，-1 表示未知。
     */
    private final long contextWindowTokens;
    /**
     * 历史最大条数，-1 表示不限。
     */
    private final int windowMaxMessages;
    /**
     * 历史最大字节数，-1 表示不限。
     */
    private final long windowMaxSizeBytes;
    /**
     * 历史占上下文窗口最大百分比（0-100），0 表示不按百分比。
     */
    private final int windowMaxTokensPercent;
    /**
     * 是否启用超限压缩。
     */
    private final boolean compressEnabled;
    /**
     * 是否启用向量化入库。
     */
    private final boolean vectorizeEnabled;
    /**
     * 每次检索返回的知识条数，0 表示不检索。
     */
    private final int retrievalCount;
    /**
     * 检索相似度阈值，取值在 [0, 1] 区间。
     */
    private final double similarityThreshold;
    /**
     * 历史工具结果单条最大保留字符数，-1 表示不限；只压缩当前提问之前的历史工具结果。
     */
    private final int toolOutputMaxChars;

    /**
     * 创建上下文历史处理控制配置，缺失字段按默认值回填并校验取值。
     *
     * @param contextWindowTokens    平台上下文窗口 token 上限（YAML 键 {@code contextWindowTokens}），可为 null
     * @param windowMaxMessages      历史最大条数（YAML 键 {@code windowMaxMessages}），可为 null
     * @param windowMaxSizeBytes     历史最大字节数（YAML 键 {@code windowMaxSizeBytes}），可为 null
     * @param windowMaxTokensPercent 历史占上下文窗口最大百分比（YAML 键 {@code windowMaxTokensPercent}），可为 null
     * @param compressEnabled        是否启用超限压缩（YAML 键 {@code compressEnabled}），可为 null
     * @param vectorizeEnabled       是否启用向量化入库（YAML 键 {@code vectorizeEnabled}），可为 null
     * @param retrievalCount         每次检索返回条数（YAML 键 {@code retrievalCount}），可为 null
     * @param similarityThreshold    检索相似度阈值（YAML 键 {@code similarityThreshold}），可为 null
     * @param toolOutputMaxChars     历史工具结果单条最大保留字符数（YAML 键 {@code toolOutputMaxChars}），可为 null
     * @throws IllegalArgumentException 任一字段取值不合法时
     */
    @JsonCreator
    public ContextControlConfiguration(
            @JsonProperty("contextWindowTokens") Long contextWindowTokens,
            @JsonProperty("windowMaxMessages") Integer windowMaxMessages,
            @JsonProperty("windowMaxSizeBytes") Long windowMaxSizeBytes,
            @JsonProperty("windowMaxTokensPercent") Integer windowMaxTokensPercent,
            @JsonProperty("compressEnabled") Boolean compressEnabled,
            @JsonProperty("vectorizeEnabled") Boolean vectorizeEnabled,
            @JsonProperty("retrievalCount") Integer retrievalCount,
            @JsonProperty("similarityThreshold") Double similarityThreshold,
            @JsonProperty("toolOutputMaxChars") Integer toolOutputMaxChars) {
        this.contextWindowTokens = contextWindowTokens != null ? contextWindowTokens : -1L;
        this.windowMaxMessages = windowMaxMessages != null ? windowMaxMessages : -1;
        this.windowMaxSizeBytes = windowMaxSizeBytes != null ? windowMaxSizeBytes : -1L;
        this.windowMaxTokensPercent = windowMaxTokensPercent != null ? windowMaxTokensPercent : 80;
        this.compressEnabled = compressEnabled != null && compressEnabled;
        this.vectorizeEnabled = vectorizeEnabled != null && vectorizeEnabled;
        this.retrievalCount = retrievalCount != null ? retrievalCount : 3;
        this.similarityThreshold = similarityThreshold != null ? similarityThreshold : 0.7;
        this.toolOutputMaxChars = toolOutputMaxChars != null
                ? toolOutputMaxChars : ContextControlConfig.DEFAULT_TOOL_OUTPUT_MAX_CHARS;
        validate();
    }

    /**
     * 校验各字段取值与 {@link ContextControlConfig} 的口径一致。
     */
    private void validate() {
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
    }

    /**
     * 转换为 core 契约的上下文控制配置 DTO。
     *
     * @return 与字段一一对应的上下文控制配置 DTO，非 null
     */
    public ContextControlConfig toContextControlConfig() {
        return ContextControlConfig.builder()
                .contextWindowTokens(contextWindowTokens)
                .windowMaxMessages(windowMaxMessages)
                .windowMaxSizeBytes(windowMaxSizeBytes)
                .windowMaxTokensPercent(windowMaxTokensPercent)
                .compressEnabled(compressEnabled)
                .vectorizeEnabled(vectorizeEnabled)
                .retrievalCount(retrievalCount)
                .similarityThreshold(similarityThreshold)
                .toolOutputMaxChars(toolOutputMaxChars)
                .build();
    }
}
