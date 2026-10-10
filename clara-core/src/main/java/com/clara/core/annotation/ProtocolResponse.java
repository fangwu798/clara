package com.clara.core.annotation;

import com.clara.enums.ProtocolEnum;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 出参协议适配：声明「响应 DTO 的某个字段，在哪种协议下的取值路径」。
 *
 * <p>与 {@link ResponseAdapter} 正交：{@link ResponseAdapter} 回答「字段在各平台 / 能力下的取值路径」，
 * 本注解只回答「同一字段在不同协议下从哪里取」。两者叠加时，本注解声明的路径<b>覆盖</b>
 * {@link ResponseAdapter} 对应路径（{@code path} / {@code streamPath} / {@code terminalPath}），
 * 未声明的维度沿用 {@link ResponseAdapter} 的取值。
 *
 * <p>路径语法与 {@link ResponseAdapter} 一致：以 {@code .} 分隔层级，数组下标写在段尾方括号里，
 * 如 {@code output[0].content[0].text}。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD})
@Repeatable(ProtocolResponses.class)
public @interface ProtocolResponse {

    /**
     * 本规则生效的协议。
     *
     * @return 协议枚举值，非 null；只有与当前协议相等时本规则才会被采用
     */
    ProtocolEnum protocol();

    /**
     * 该协议下的同步取值路径。
     *
     * @return JSON 路径；空串表示沿用 {@link ResponseAdapter#path()}
     */
    String path() default "";

    /**
     * 该协议下的流式增量取值路径。
     *
     * @return JSON 路径；空串表示沿用 {@link ResponseAdapter#streamPath()}
     */
    String streamPath() default "";

    /**
     * 该协议下流式增量对应的 SSE 事件名。
     *
     * <p>Responses 协议用 {@code response.output_text.delta} / {@code response.reasoning_text.delta} 等具名
     * 事件区分增量类型，且不同事件的增量字段都叫 {@code delta}；只凭 {@link #streamPath()} 无法区分正文与
     * 思维链。声明本属性后，流式累加只在事件名匹配时取值；空串表示不按事件名过滤（兼容 Chat Completions
     * 这类只发 {@code data} 行、事件名恒为缺省值的协议）。
     *
     * @return SSE 事件名；空串表示不限制
     */
    String streamEvent() default "";

    /**
     * 该协议下是否禁用流式增量、改用终态 {@link #path()} 一次性解析。
     *
     * <p>Responses 协议的 {@code function_call} 在流式里通过 {@code response.completed} 事件携带完整参数，
     * 不像 Chat Completions 那样逐分片下发 {@code delta.tool_calls}；此类字段声明 {@code streamDisabled=true}
     * 后，流式累加把它归入「每个数据块按 {@code path} 取一次」的终态解析，而不是按 {@link #streamPath()} 增量
     * 累加。默认 false 表示沿用 {@link ResponseAdapter} 的流式增量语义。
     *
     * @return true 表示该协议下不走流式增量；false（默认）表示按 streamPath 增量累加
     */
    boolean streamDisabled() default false;

    /**
     * 该协议下的结束原因取值路径。
     *
     * @return JSON 路径；空串表示沿用 {@link ResponseAdapter#terminalPath()}
     */
    String terminalPath() default "";
}
