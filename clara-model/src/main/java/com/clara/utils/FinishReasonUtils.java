package com.clara.utils;

import com.clara.enums.FinishReasonEnum;

/**
 * 把 OpenAI 兼容协议返回的 {@code finish_reason} 文本映射为领域结束原因。
 *
 * <p>定位：协议字段 → 领域枚举的纯映射，供兼容协议的同步解码（非流式响应体解析）与流式累加
 * （读取增量时解析同一字段）共用，避免两处各写一份映射表。
 *
 * <p>使用方法：
 * <ul>
 *   <li>无需实例化——构造器私有，全部方法为 {@code static}；</li>
 *   <li>拿到平台返回的 {@code finish_reason} 原始文本后调用 {@link #of(String)}，
 *       把结果写入响应或流结束事件，由上层据此决定是否续跑或告警。</li>
 * </ul>
 *
 * <p>线程安全：无状态、无可变字段，可跨线程并发调用。
 *
 * <p>失败语义：不抛异常——未知取值与 null 一律归为 {@link FinishReasonEnum#ERROR}，
 * 由上层决定是否告警，而不是在解析阶段直接中断调用。
 */
public final class FinishReasonUtils {

    private FinishReasonUtils() {
    }

    /**
     * 映射结束原因。
     *
 * <p>取值约定：Chat Completions 的 {@code stop} / Responses 的 {@code completed} / Anthropic 的
 * {@code end_turn}、{@code stop_sequence} → {@link FinishReasonEnum#STOP}、
 * {@code length} / {@code incomplete} / Anthropic 的 {@code max_tokens} → {@link FinishReasonEnum#LENGTH}、
 * {@code tool_calls}、{@code function_call} 与 Anthropic 的 {@code tool_use} → {@link FinishReasonEnum#TOOL_CALLS}、
 * {@code content_filter} / Anthropic 的 {@code refusal} → {@link FinishReasonEnum#CONTENT_FILTER}、
 * {@code failed} → {@link FinishReasonEnum#ERROR}。
     *
     * @param value 平台返回的 {@code finish_reason} 或 Responses 的 {@code status}，可为 null
     * @return 领域结束原因；未知取值或缺失时为 {@link FinishReasonEnum#ERROR}
     */
    public static FinishReasonEnum of(String value) {
        if ("stop".equals(value) || "completed".equals(value)
                || "end_turn".equals(value) || "stop_sequence".equals(value)) {
            return FinishReasonEnum.STOP;
        }
        if ("length".equals(value) || "incomplete".equals(value) || "max_tokens".equals(value)) {
            return FinishReasonEnum.LENGTH;
        }
        if ("tool_calls".equals(value) || "function_call".equals(value) || "tool_use".equals(value)) {
            return FinishReasonEnum.TOOL_CALLS;
        }
        if ("content_filter".equals(value) || "refusal".equals(value)) {
            return FinishReasonEnum.CONTENT_FILTER;
        }
        return FinishReasonEnum.ERROR;
    }
}
