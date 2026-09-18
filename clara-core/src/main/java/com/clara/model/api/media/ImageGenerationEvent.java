package com.clara.model.api.media;

import com.clara.kernel.ClaraError;

/**
 * 表示图片生成流产生的事件。
 *
 * <p>用途：流式生图会逐张推送结果，调用方用本类观察「第几张图好了 / 第几张失败了 / 整条流是否结束」，
 * 而不必自己解析平台的事件结构。
 *
 * <p>与对话流事件的差异：图片流没有文本增量，只有「整张图完成」这一粒度；组图场景下每张图各有一个
 * {@link Type#IMAGE} 事件，{@link #getImageIndex()} 给出它在组图中的位置。
 *
 * <p>注意：事件顺序由 {@link #getSequence()} 保证；{@link Type#RESPONSE_COMPLETE} 与 {@link Type#ERROR}
 * 是终态事件，一次流中只会发出其中一个，且只发一次。
 */
public final class ImageGenerationEvent {
    /**
     * 定义事件类型的可选取值。
     */
    public enum Type {
        /**
         * 单张图片已生成并落盘，{@link #getAssetId()} 非 null。
         */
        IMAGE,
        /**
         * 单张图片生成失败，但整条流可能继续，{@link #getError()} 与 {@link #getImageIndex()} 非 null。
         */
        IMAGE_FAILED,
        /**
         * 终态事件：本次请求正常收尾，汇总结果放在 {@link #getResult()}（已生成图片的资产 ID、平台元数据）。
         */
        RESPONSE_COMPLETE,
        /**
         * 终态事件：整条流失败（如参数非法、鉴权失败、连接中断），错误放在 {@link #getError()}。
         */
        ERROR
    }

    private final Type type;
    private final long sequence;
    private final int imageIndex;
    private final String assetId;
    private final ImageGenerationResult result;
    private final ClaraError error;

    /**
     * 创建单个图片事件。
     *
     * @param type       事件类型
     * @param sequence   事件序号，从 1 开始且严格递增
     * @param imageIndex 图片在组图中的位置，从 0 开始
     * @param assetId    已落盘图片的资产 ID，非 IMAGE 事件时为 null
     * @param result     汇总结果，非 RESPONSE_COMPLETE 事件时为 null
     * @param error      错误信息，非 IMAGE_FAILED/ERROR 事件时为 null
     * @throws IllegalArgumentException 类型为 null、序号非正，或与类型配套的字段缺失时
     */
    public ImageGenerationEvent(Type type, long sequence, int imageIndex, String assetId,
            ImageGenerationResult result, ClaraError error) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (sequence <= 0L || imageIndex < 0) {
            throw new IllegalArgumentException("sequence must be positive and imageIndex must not be negative");
        }
        this.type = type;
        this.sequence = sequence;
        this.imageIndex = imageIndex;
        this.assetId = assetId;
        this.result = result;
        this.error = error;
    }

    /**
     * 读取事件类型。
     *
     * @return 事件类型
     */
    public Type getType() {
        return type;
    }

    /**
     * 读取事件序号。
     *
     * @return 从 1 开始的事件序号
     */
    public long getSequence() {
        return sequence;
    }

    /**
     * 读取图片序号。
     *
     * @return 图片在组图中的位置，从 0 开始
     */
    public int getImageIndex() {
        return imageIndex;
    }

    /**
     * 读取已落盘图片的资产 ID。
     *
     * @return 资产 ID；非 IMAGE 事件时为 null
     */
    public String getAssetId() {
        return assetId;
    }

    /**
     * 读取汇总结果。
     *
     * @return 汇总结果；非 RESPONSE_COMPLETE 事件时为 null
     */
    public ImageGenerationResult getResult() {
        return result;
    }

    /**
     * 读取错误信息。
     *
     * @return 错误信息；非 IMAGE_FAILED/ERROR 事件时为 null
     */
    public ClaraError getError() {
        return error;
    }
}
