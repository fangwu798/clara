package com.clara.annoation;

import com.clara.enums.ModelProviderEnum;
import com.clara.kernel.message.provider.ProviderRequestEncoder;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 平台标记：声明某个字段归属的模型服务提供商。
 *
 * <p>本注解运行时保留（{@code RetentionPolicy.RUNTIME}）、只作用于字段，是否为字段级平台适配规则由读取方
 * 决定。当前入参编码链路读取的是 {@link ProviderAdapter#type()}（见
 * {@link ProviderRequestEncoder}），本注解在仓库内暂无标注点。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD})
public @interface ModelProvider {

    /**
     * 被标记字段归属的平台。
     *
     * @return 平台枚举值，非 null；缺省为 {@link ModelProviderEnum#DEEPSEEK}
     */
    ModelProviderEnum value() default ModelProviderEnum.DEEPSEEK;
}
