package com.clara.core.transport.sse;

import lombok.Data;

/**
 * SSE 解析上限配置。
 */
@Data
public final class SseParserConfig {

    public static final int DEFAULT_MAX_LINE_BYTES = 64 * 1024;
    public static final int DEFAULT_MAX_EVENT_BYTES = 1024 * 1024;
    public static final int DEFAULT_MAX_EVENTS = 200_000;
    public static final long DEFAULT_MAX_TOTAL_BYTES = 32L * 1024 * 1024;
    public static final String DEFAULT_EVENT_TYPE = "message";

    /**
     * 单行最大字节数。
     */
    private final int maxLineBytes;

    /**
     * 单个事件最大字节数。
     */
    private final int maxEventBytes;

    /**
     * 单次解析允许的最大事件数。
     */
    private final int maxEvents;

    /**
     * 单次解析允许的最大总字节数。
     */
    private final long maxTotalBytes;

    /**
     * 流结束时是否派发未以空行结尾的尾部事件。
     */
    private final boolean dispatchFinalEventOnEof;

    /**
     * 创建 SSE 解析配置。
     *
     * @param maxLineBytes            单行最大字节数，必须为正
     * @param maxEventBytes           单事件最大字节数，必须为正且不超过 maxTotalBytes
     * @param maxEvents               最大事件数，必须为正
     * @param maxTotalBytes           单次解析最大总字节数，必须为正
     * @param dispatchFinalEventOnEof 流结束时是否派发未以空行收尾的最后一个事件
     */
    public SseParserConfig(int maxLineBytes, int maxEventBytes, int maxEvents, long maxTotalBytes, boolean dispatchFinalEventOnEof) {
        if (maxLineBytes <= 0 || maxEventBytes <= 0 || maxEvents <= 0 || maxTotalBytes <= 0L) {
            throw new IllegalArgumentException("sse limits must be positive");
        }
        if (maxEventBytes > maxTotalBytes) {
            throw new IllegalArgumentException("event limit exceeds total limit");
        }
        this.maxLineBytes = maxLineBytes;
        this.maxEventBytes = maxEventBytes;
        this.maxEvents = maxEvents;
        this.maxTotalBytes = maxTotalBytes;
        this.dispatchFinalEventOnEof = dispatchFinalEventOnEof;
    }

    /**
     * 返回模型侧默认配置：严格按空行收尾，丢弃 EOF 残事件。
     *
     * @return 默认配置，非 null
     */
    public static SseParserConfig defaults() {
        return new SseParserConfig(DEFAULT_MAX_LINE_BYTES, DEFAULT_MAX_EVENT_BYTES, DEFAULT_MAX_EVENTS, DEFAULT_MAX_TOTAL_BYTES, false);
    }

}
