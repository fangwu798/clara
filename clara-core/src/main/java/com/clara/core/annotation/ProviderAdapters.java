package com.clara.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@link ProviderAdapter} 的容器注解。
 *
 * <p>为什么需要：同一个字段在不同平台上的适配规则不同（例如 {@code top_p} 在 DeepSeek 默认 1、
 * 在火山方舟默认 0.7；{@code top_k} 两家都不支持），因此一个字段需要声明多条适配规则。
 * Java 中同一注解要在同一元素上重复出现，必须由 {@code @Repeatable} 指向一个容器注解。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD})
public @interface ProviderAdapters {

    /**
     * 该字段上的全部平台适配规则。
     *
     * @return 适配规则数组
     */
    ProviderAdapter[] value();
}
