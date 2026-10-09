package com.clara.annoation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@link ResponseAdapter} 的容器注解。
 *
 * <p>为什么需要：同一个响应字段在不同平台上的取值路径不同（例如缓存命中量在 DeepSeek 是
 * {@code usage.prompt_cache_hit_tokens}，在方舟 / OpenAI / 通义是
 * {@code usage.prompt_tokens_details.cached_tokens}），因此一个字段需要声明多条出参适配规则。Java 中同一注解
 * 要在同一元素上重复出现，必须由 {@code @Repeatable} 指向一个容器注解。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD})
public @interface ResponseAdapters {

    /**
     * 该字段上的全部出参适配规则。
     *
     * @return 适配规则数组
     */
    ResponseAdapter[] value();
}
