package com.clara.annoation;

import com.clara.enums.ModelProviderEnum;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD})
public @interface ModelProvider {
    ModelProviderEnum value() default ModelProviderEnum.DEEPSEEK;
}
