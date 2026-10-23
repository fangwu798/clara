package com.clara.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 公共 API 契约标记。
 *
 * <p>被标注的类型、方法或字段属于对外承诺的稳定接口：次版本升级保持二进制兼容，只在主版本升级时允许
 * 不兼容变更。宿主应用可以放心地直接依赖这些符号。
 *
 * <p>未被标注的公开类型默认视为内部实现，可能随版本调整；需要长期依赖时请先确认其是否已标注本注解。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface PublicApi {

    /**
     * 该 API 的稳定级别说明，用于发布说明与兼容性评审。
     *
     * @return 稳定级别描述，默认为空串
     */
    String value() default "";
}
