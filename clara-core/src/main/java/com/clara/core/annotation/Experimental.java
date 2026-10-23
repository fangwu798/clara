package com.clara.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 实验性 API 标记。
 *
 * <p>该 API 已可用但不承诺兼容：签名、语义乃至是否存在都可能在任意版本调整。适合试用与反馈，
 * 不建议在生产代码中形成硬依赖。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface Experimental {
}
