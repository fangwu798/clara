package com.clara.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 内部实现标记。
 *
 * <p>被标注的类型即使声明为 public，也只服务于 Clara 内部模块之间的协作，不属于对外契约。宿主应用
 * 不应直接引用，否则升级时不保证兼容。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface Internal {
}
