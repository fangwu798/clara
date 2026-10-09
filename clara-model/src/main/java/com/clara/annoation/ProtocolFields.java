package com.clara.annoation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@link ProtocolField} 的容器注解。
 *
 * <p>为什么需要：同一请求字段在不同协议下的线格式名不同（如对话消息在 Chat Completions 是 {@code messages}、
 * 在 Responses 是 {@code input}），因此一个字段需要声明多条协议适配规则。Java 中同一注解要在同一元素上
 * 重复出现，必须由 {@code @Repeatable} 指向一个容器注解。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD})
public @interface ProtocolFields {

    /**
     * 该字段上的全部协议适配规则。
     *
     * @return 协议适配规则数组
     */
    ProtocolField[] value();
}
