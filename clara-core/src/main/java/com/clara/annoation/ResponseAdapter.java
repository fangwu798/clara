package com.clara.annoation;

import com.clara.enums.CapabilityKindEnum;
import com.clara.enums.ModelProviderEnum;
import com.clara.enums.StreamEventEnum;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 出参适配：声明「响应 DTO 的某个字段，在各平台响应体里的取值路径」。
 *
 * <p>与入参侧的 {@link ProviderAdapter} 对称：入参注解解决「同一个请求字段在不同平台怎么下发」，
 * 本注解解决「同一个响应字段在不同平台从哪里取」。本注解只由解码器
 * {@link com.clara.utils.deserialize.ProviderResponseDecoder} 反射读取；两者配合后，
 * {@link com.clara.kernel.http.HttpResponse} 这类统一响应对象即可承载所有平台的返回，不需要为每个平台写解码器。
 *
 * <p>路径语法：以 {@code .} 分隔层级，数组下标写在段尾方括号里，例如
 * {@code choices[0].message.content}、{@code usage.prompt_tokens_details.cached_tokens}、
 * {@code data}（整段数组）。
 *
 * <p>维度说明（未声明的维度表示不约束）：
 * <ul>
 *   <li>{@link #type()}：生效平台；<b>空数组表示全部平台通用</b>（OpenAI 兼容家族的字段路径一致，通常不必列举），
 *       只需在平台差异处显式列出平台；</li>
 *   <li>{@link #capability()}：生效能力，默认 {@link CapabilityKindEnum#CHAT}；图片等能力需显式声明；</li>
 *   <li>{@link #path()}：一次性取值路径，{@link #streamPath()} 为空时同步解码与流式的每个数据块都按它取一次；</li>
 *   <li>{@link #streamPath()}：流式增量里的取值路径；空表示流式不取该字段；</li>
 *   <li>{@link #event()}：{@link #streamPath()} 命中时要发射的流事件类型；</li>
 *   <li>{@link #terminalPath()}：结束原因路径，命中非空值即把该值解码进字段（如 {@code finish_reason}），
 *       <b>不</b>构成流终止条件——终止条件是 SSE 的 {@code [DONE]}；</li>
 *   <li>{@link #required()}：同步解码时该路径必须存在，缺失即按协议错误处理；</li>
 *   <li>{@link #note()}：取值口径等补充说明。</li>
 * </ul>
 *
 * <p>同一字段需要适配多个平台时可直接重复标注（由 {@link ResponseAdapters} 承载）；解码时取<b>第一条</b>
 * 命中的规则，因此平台专属规则应写在通用规则之前。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD})
@Repeatable(ResponseAdapters.class)
public @interface ResponseAdapter {

    /**
     * 生效平台。
     *
     * @return 平台列表；空数组表示全部平台通用
     */
    ModelProviderEnum[] type() default {};

    /**
     * 生效能力。
     *
     * @return 能力种类，默认文本对话
     */
    CapabilityKindEnum capability() default CapabilityKindEnum.CHAT;

    /**
     * 取值路径。
     *
     * <p>同步解码按本路径取值；流式下若同时声明了 {@link #streamPath()}，该字段按增量路径处理，否则每个
     * 数据块都按本路径取一次（{@code id} / {@code model} / {@code usage.*} 集中在首块或末块，不在增量里）。
     *
     * @return JSON 路径；空串表示该字段不按本路径取值
     */
    String path() default "";

    /**
     * 流式响应中的增量取值路径。
     *
     * @return JSON 路径；空串表示流式响应不解析该字段
     */
    String streamPath() default "";

    /**
     * 流式增量命中时要发射的事件类型。
     *
     * @return 事件类型；仅当 {@link #streamPath()} 非空时有意义
     */
    StreamEventEnum event() default StreamEventEnum.TEXT_DELTA;

    /**
     * 结束原因路径。
     *
     * <p>命中时只把该路径的值解码进字段（典型是 {@code choices[0].finish_reason}），<b>不作为流终止条件</b>：
     * 开启 {@code stream_options.include_usage} 后平台还会在结束原因之后补发只带 {@code usage} 的数据块，
     * 提前终止会丢掉 token 用量，因此解码器只在收到 SSE 的 {@code [DONE]} 时结束本轮流。
     *
     * @return JSON 路径；空串表示该字段不参与结束原因取值
     */
    String terminalPath() default "";

    /**
     * 该字段在同步响应中是否必需。
     *
     * @return true 表示路径缺失时按协议错误处理，而不是跳过
     */
    boolean required() default false;

    /**
     * 补充说明。
     *
     * @return 取值口径、平台限制等说明；默认无
     */
    String note() default "";
}
