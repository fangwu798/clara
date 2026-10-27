package com.clara.adapter.console;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 趋势图的一个采样点：记录时刻、全部平台累计总量，以及各模型的累计用量。
 *
 * <p>不可变值对象；模型映射在构造时复制并做非负归一，读取方无法修改采样数据。
 */
final class UsageTrendSample {

    /** 采样时刻，毫秒时间戳。 */
    private final long timestampMillis;
    /** 全部平台累计 token。 */
    private final long totalTokens;
    /** 模型到累计 token 的映射；旧采样方法没有模型明细时为空映射。 */
    private final Map<String, Long> modelTokens;

    /**
     * 创建采样点。
     *
     * @param timestampMillis 采样时刻，毫秒时间戳
     * @param totalTokens     全部平台累计 token，负数按 0 处理
     * @param modelTokens     各模型累计 token，可为 null（视为无明细）
     */
    UsageTrendSample(long timestampMillis, long totalTokens, Map<String, Long> modelTokens) {
        this.timestampMillis = timestampMillis;
        this.totalTokens = Math.max(0L, totalTokens);
        Map<String, Long> copy = new LinkedHashMap<>();
        if (modelTokens != null) {
            for (Map.Entry<String, Long> entry : modelTokens.entrySet()) {
                if (entry.getKey() != null) {
                    long value = entry.getValue() == null ? 0L : entry.getValue();
                    copy.put(entry.getKey(), Math.max(0L, value));
                }
            }
        }
        this.modelTokens = Collections.unmodifiableMap(copy);
    }

    /**
     * 获取采样时刻。
     *
     * @return 毫秒时间戳
     */
    long getTimestampMillis() {
        return timestampMillis;
    }

    /**
     * 获取全部平台累计 token。
     *
     * @return 非负累计值
     */
    long getTotalTokens() {
        return totalTokens;
    }

    /**
     * 获取各模型累计 token。
     *
     * @return 不可变映射，非 null；无模型明细时为空映射
     */
    Map<String, Long> getModelTokens() {
        return modelTokens;
    }
}
