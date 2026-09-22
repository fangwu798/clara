package com.clara.annoation;

import com.clara.enums.ProtocolEnum;
import com.clara.kernel.message.provider.ProviderRequestEncoder;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 入参协议适配：声明「请求对象的某个字段，在哪种协议下的线格式名」。
 *
 * <p>与 {@link ProviderAdapter} 正交：{@link ProviderAdapter} 回答「字段在哪个平台、哪种能力下是否支持 /
 * 默认值 / 取值范围」，本注解只回答「同一字段在不同协议下叫什么名字」以及「某协议是否支持该字段」。
 * 两者叠加时，本注解声明的名字<b>覆盖</b> {@link ProviderAdapter#name()} 与 {@code @JsonProperty} 决定的
 * 线格式名；因此不要在 {@link ProviderAdapter} 里堆协议差异，协议差异收敛到本注解。
 *
 * <p>谁创建、谁调用：由参数类（如 {@link com.clara.kernel.message.ModelParameters}）在字段上标注，
 * 只被编码器 {@link ProviderRequestEncoder} 反射读取。同一字段需要适配多个
 * 协议时由 {@link ProtocolFields} 承载重复标注。
 *
 * <p>命名规则：{@link #name()} 与 {@link ProviderAdapter#name()} 一致——空串表示沿用原线格式名，
 * 可用点号表示嵌套层级（如 {@code "stream_options.include_usage"}）。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD})
@Repeatable(ProtocolFields.class)
public @interface ProtocolField {

    /**
     * 本规则生效的协议。
     *
     * @return 协议枚举值，非 null；只有与当前协议相等时本规则才会被采用
     */
    ProtocolEnum protocol();

    /**
     * 该协议下的线格式名。
     *
     * @return 线格式名，可含点号表示嵌套；空串表示沿用原线格式名（{@link ProviderAdapter#name()} 或
     *         {@code @JsonProperty}）
     */
    String name() default "";

    /**
     * 该协议是否支持本字段。
     *
     * @return false 表示该协议下必须丢弃本字段（如 Chat Completions 的 {@code stream_options} 在 Responses
     *         协议下不存在）
     */
    boolean supported() default true;
}
