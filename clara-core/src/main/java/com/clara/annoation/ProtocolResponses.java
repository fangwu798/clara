package com.clara.annoation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@link ProtocolResponse} 的容器注解。
 *
 * <p>为什么需要：同一响应字段在不同协议下的取值路径不同（如正文在 Chat Completions 是
 * {@code choices[0].message.content}、在 Responses 是 {@code output[0].content[0].text}），因此一个字段需要
 * 声明多条协议适配规则。Java 中同一注解要在同一元素上重复出现，必须由 {@code @Repeatable} 指向容器注解。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD})
public @interface ProtocolResponses {

    /**
     * 该字段上的全部协议适配规则。
     *
     * @return 协议适配规则数组
     */
    ProtocolResponse[] value();
}
