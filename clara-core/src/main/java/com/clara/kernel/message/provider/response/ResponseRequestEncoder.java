package com.clara.kernel.message.provider.response;

import com.clara.annoation.ProtocolField;
import com.clara.enums.ModelProviderEnum;
import com.clara.enums.ProtocolEnum;
import com.clara.kernel.api.message.IContent;
import com.clara.kernel.message.provider.AbstractRequestEncoder;
import com.clara.tool.ToolContext;
import com.clara.utils.CodexTuiGenerator;
import com.clara.utils.deserialize.ResponseInputConverter;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Responses 协议的请求编码器。
 *
 * <p>用 {@link ProtocolField} 映射字段名（如 {@code messages} → {@code input}、{@code max_tokens} →
 * {@code max_output_tokens}），用 {@link ResponseInputConverter} 转换消息 / 工具声明结构，并为 Codex 模拟补齐
 * {@code reasoning.context} / {@code store} / {@code include} / {@code text} 等字段。
 */
public final class ResponseRequestEncoder extends AbstractRequestEncoder {

    @Override
    protected String protocolName(Field field, String baseName) {
        for (ProtocolField protocolField : field.getDeclaredAnnotationsByType(ProtocolField.class)) {
            if (protocolField.protocol() == ProtocolEnum.RESPONSE) {
                if (!protocolField.supported()) {
                    return null;
                }
                return protocolField.name().isEmpty() ? baseName : protocolField.name();
            }
        }
        return baseName;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Object protocolValue(Field field, Object value) {
        if (value == null || !(value instanceof List)) {
            return value;
        }
        List<?> list = (List<?>) value;
        if (list.isEmpty()) {
            return value;
        }
        Object first = list.get(0);
        if (first instanceof IContent) {
            return ResponseInputConverter.toInput((List<IContent>) value);
        }
        if (first instanceof ToolContext) {
            return ResponseInputConverter.toTools((List<ToolContext>) value);
        }
        return value;
    }

    @Override
    protected void postProcessBody(Map<String, Object> body, ModelProviderEnum provider, Map<String, String> authorization) {
        if (provider != ModelProviderEnum.OPENAI) {
            return;
        }
        Map<String, String> clientMetadata = CodexTuiGenerator.buildClientMetadata(authorization);
        if (clientMetadata.isEmpty()) {
            return;
        }
        body.put("client_metadata", clientMetadata);
        injectCodexFields(body);
    }

    @SuppressWarnings("unchecked")
    private static void injectCodexFields(Map<String, Object> body) {
        Object reasoning = body.get("reasoning");
        if (reasoning instanceof Map && !((Map<String, Object>) reasoning).containsKey("context")) {
            ((Map<String, Object>) reasoning).put("context", "all_turns");
        }
        if (!body.containsKey("store")) {
            body.put("store", Boolean.FALSE);
        }
        if (!body.containsKey("include")) {
            body.put("include", Collections.singletonList("reasoning.encrypted_content"));
        }
        if (!body.containsKey("text")) {
            Map<String, Object> text = new LinkedHashMap<String, Object>();
            text.put("verbosity", "low");
            body.put("text", text);
        }
    }
}
