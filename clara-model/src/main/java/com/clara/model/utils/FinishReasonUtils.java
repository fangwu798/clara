package com.clara.model.utils;

import com.clara.model.api.FinishReason;

import com.clara.model.provider.compatible.OpenAiCompatibleStreamAccumulator;

/**
 * 把 OpenAI 兼容协议返回的 {@code finish_reason} 文本映射为领域结束原因。
 *
 * <p>用途：非流式（{@link OpenAiCompatibleChatCodec}）与流式
 * （{@link OpenAiCompatibleStreamAccumulator}）需要对同一个字段做同一套映射，集中在此避免两处各写一份。
 *
 * <p>取值约定：{@code stop} → {@link FinishReason#STOP}、{@code length} → {@link FinishReason#LENGTH}、
 * {@code tool_calls} 与 {@code function_call} → {@link FinishReason#TOOL_CALLS}、{@code content_filter} →
 * {@link FinishReason#CONTENT_FILTER}；未知取值与 null 一律归为 {@link FinishReason#ERROR}，
 * 由上层决定是否告警，而不是在解析阶段直接中断。
 */
public class FinishReasonUtils {

    /**
     * 映射结束原因。
     *
     * @param value 平台返回的 {@code finish_reason}，可为 null
     * @return 领域结束原因；未知取值或缺失时为 {@link FinishReason#ERROR}
     */
   public static FinishReason of(String value) {
        if ("stop".equals(value)) {
            return FinishReason.STOP;
        }
        if ("length".equals(value)) {
            return FinishReason.LENGTH;
        }
        if ("tool_calls".equals(value) || "function_call".equals(value)) {
            return FinishReason.TOOL_CALLS;
        }
        if ("content_filter".equals(value)) {
            return FinishReason.CONTENT_FILTER;
        }
        return FinishReason.ERROR;
    }
}
