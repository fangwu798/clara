package com.clara.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 思考模式的取值。
 *
 * <p>线格式位置：{@code "thinking":{"type":"<取值>"}}。DeepSeek 只接受 {@code enabled}/{@code disabled}；
 * 火山方舟额外接受 {@code auto}（由模型自行判断是否思考），把 {@code auto} 下发给 DeepSeek 会被拒绝。
 */
public enum ThinkingEnum {

    /**
     * 强制先思考再回答，线格式 {@code "enabled"}。
     */
    ENABLED("enabled"),
    /**
     * 关闭思考，直接回答，线格式 {@code "disabled"}。
     */
    DISABLED("disabled"),
    /**
     * 由模型自行判断是否需要思考，线格式 {@code "auto"}；仅火山方舟支持。
     */
    AUTO("auto");

    /** 请求体中的线格式取值。 */
    private final String value;

    ThinkingEnum(String value) {
        this.value = value;
    }

    /**
     * 返回请求体中的线格式取值。
     *
     * @return 线格式字面量，非 null
     */
    @JsonValue
    public String value() {
        return value;
    }

    /**
     * 按线格式取值解析枚举。
     *
     * @param value 线格式字面量
     * @return 对应枚举，非 null
     * @throws IllegalArgumentException 取值不受支持时
     */
    @JsonCreator
    public static ThinkingEnum fromValue(String value) {
        for (ThinkingEnum item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("unknown thinking type: " + value);
    }
}
