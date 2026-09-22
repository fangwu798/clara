package com.clara.kernel.message.provider.completion;

import com.clara.enums.ModelProviderEnum;
import com.clara.kernel.message.provider.AbstractRequestEncoder;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Chat Completions 协议的请求编码器。
 *
 * <p>不做协议化覆盖：字段名沿用 {@code @JsonProperty} / 字段名，字段值不做结构转换，也不补充平台特有字段。
 */
public final class CompletionRequestEncoder extends AbstractRequestEncoder {

    @Override
    protected String protocolName(Field field, String baseName) {
        return baseName;
    }

    @Override
    protected Object protocolValue(Field field, Object value) {
        return value;
    }

    @Override
    protected void postProcessBody(Map<String, Object> body, ModelProviderEnum provider, Map<String, String> authorization) {
        // Chat Completions 无补充字段
    }
}
