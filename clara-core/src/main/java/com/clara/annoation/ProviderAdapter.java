package com.clara.annoation;

import com.clara.enums.CapabilityKindEnum;
import com.clara.enums.ModelProviderEnum;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 在调用大模型之前需要对公共参数进行模型适配
 * 不同的模型对参数的要求有一些细微的差异
 * 所有定义如下适配器来进行字段级别的适配
 *
 * <p>维度说明（各维度之间是补充关系，未声明的维度表示不约束）：
 * <ul>
 *   <li>{@link #name()}：该平台 + 该能力下的线格式名，空串表示沿用字段上的 {@code @JsonProperty} 或字段名；</li>
 *   <li>{@link #supported()}：平台是否支持该字段；{@code false} 表示下发前必须丢弃；</li>
 *   <li>{@link #streamingOnly()}：是否只在流式请求（{@code stream = true}）时下发；</li>
 *   <li>{@link #defaultValue()}：平台默认值，字段缺省时按该值下发；空串表示沿用平台自身默认值；</li>
 *   <li>{@link #allowed()}：允许的取值集合，用于枚举型 / 字符串型字段；</li>
 *   <li>{@link #min()} / {@link #max()}：数值型字段的闭区间边界；</li>
 *   <li>{@link #maxCount()}：数组型字段的最大元素个数；</li>
 *   <li>{@link #note()}：上面维度表达不了的额外约束（如「深度思考模型不支持该字段」）。</li>
 * </ul>
 *
 * <p>同一字段需要适配多个平台时可直接重复标注（由 {@link ProviderAdapters} 承载）。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD})
@Repeatable(ProviderAdapters.class)
public @interface ProviderAdapter {

    /**
     * 适配平台
     *
     * @return
     */
    ModelProviderEnum type() default ModelProviderEnum.DEEPSEEK;

    /**
     * 适配场景 文本对话 生成图片  生成音频  生成适配
     *
     * @return
     */
    CapabilityKindEnum capability() default CapabilityKindEnum.CHAT;

    /**
     * 该平台 + 该能力下的线格式名。
     *
     * <p>什么时候需要它：同一个字段在不同能力上的线格式不一样时（如 {@code response_format} 在对话侧是对象
     * {@code {"type":"text"}}、在生图侧是字符串 {@code "url"}），字段上的 {@code @JsonProperty} 只能声明
     * 一个名字，就只能把差异放到这里。
     *
     * <p>写法：与 {@code @JsonProperty} 一致；用点号表示嵌套层级，如
     * {@code "response_format.type"} 会编码成 {@code {"response_format":{"type":"text"}}}。
     *
     * @return 线格式名；空串表示沿用 {@code @JsonProperty}，未标注时用字段名
     */
    String name() default "";

    /**
     * 匹配之后的默认值：字段缺省时按该值下发。
     *
     * @return 平台默认值的字面量；空串表示不在本地兜默认值，沿用平台自身默认
     */
    String defaultValue() default "";

    /**
     * 该平台是否支持本字段。
     *
     * @return false 表示平台不支持（如 DeepSeek 已废弃的 {@code frequency_penalty}），下发前必须丢弃
     */
    boolean supported() default true;

    /**
     * 是否只在流式请求时下发本字段。
     *
     * <p>什么时候需要它：字段与流式强绑定，非流式下发会被平台拒绝。典型例子是
     * {@code stream_options.include_usage}——它只在 {@code stream = true} 时合法，而流式响应又必须靠它
     * 才会带回 token 用量。
     *
     * @return true 表示仅当请求的 {@code stream} 为 true 时才下发；false（默认）表示不限制
     */
    boolean streamingOnly() default false;

    /**
     * 该平台允许的取值集合。
     *
     * @return 允许的字面量取值；空数组表示不限制
     */
    String[] allowed() default {};

    /**
     * 数值下界（含）。
     *
     * @return 下界；默认无下界
     */
    double min() default Double.NEGATIVE_INFINITY;

    /**
     * 数值上界（含）。
     *
     * @return 上界；默认无上界
     */
    double max() default Double.POSITIVE_INFINITY;

    /**
     * 集合型字段的元素个数上限。
     *
     * @return 最大元素个数；0 表示不限制
     */
    int maxCount() default 0;

    /**
     * 额外约束或前提条件。
     *
     * @return 无法用其他维度表达的限制说明；默认无
     */
    String note() default "";
}
