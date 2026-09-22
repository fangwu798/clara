package com.clara.enums;

/**
 * 思考模式的取值，常量名即请求体中的线格式字面量。
 *
 * <p>线格式位置：{@code "thinking":{"type":"<取值>"}}。DeepSeek 只接受 {@code enabled}/{@code disabled}；
 * 火山方舟额外接受 {@code auto}（由模型自行判断是否思考），把 {@code auto} 下发给 DeepSeek 会被拒绝。
 */
public enum ThinkingEnum {

    /**
     * 强制先思考再回答，线格式 {@code "enabled"}。
     */
    enabled,
    /**
     * 关闭思考，直接回答，线格式 {@code "disabled"}。
     */
    disabled,
    /**
     * 由模型自行判断是否需要思考，线格式 {@code "auto"}；仅火山方舟支持。
     */
    auto
}
