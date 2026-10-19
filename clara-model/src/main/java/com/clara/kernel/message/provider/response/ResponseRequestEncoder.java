package com.clara.kernel.message.provider.response;

import com.clara.core.annotation.ProtocolField;
import com.clara.enums.ModelProviderEnum;
import com.clara.enums.ProtocolEnum;
import com.clara.core.model.IRequest;
import com.clara.core.message.IContent;
import com.clara.kernel.message.BasicParameters;
import com.clara.kernel.message.provider.AbstractRequestEncoder;
import com.clara.dto.tool.ToolContext;
import com.clara.utils.CodexTuiGenerator;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Responses 协议的请求编码器。
 *
 * <p>用 {@link ProtocolField} 映射字段名（如 {@code messages} → {@code input}、{@code max_tokens} →
 * {@code max_output_tokens}），消息 / 工具声明结构由 {@link IContent} / {@link ToolContext} 自渲染，
 * 系统指令提升为顶层 {@code instructions}，并为 Codex 模拟补齐
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
        if (!(value instanceof List)) {
            return value;
        }
        List<?> list = (List<?>) value;
        if (list.isEmpty()) {
            return value;
        }
        Object first = list.get(0);
        if (first instanceof IContent) {
            List<Map<String, Object>> input = new ArrayList<>();
            for (Object o : list) {
                IContent content = (IContent) o;
                if (!content.isSystemInstruction()) {
                    input.addAll(content.toResponseInputItems());
                }
            }
            return input;
        }
        if (first instanceof ToolContext) {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (Object o : list) {
                tools.add(((ToolContext) o).toResponseTool());
            }
            return tools;
        }
        return value;
    }

    @Override
    protected void postProcessBody(Map<String, Object> body, IRequest request, ModelProviderEnum provider, Map<String, String> authorization) {
        if (request instanceof BasicParameters) {
            injectInstructions(body, (BasicParameters) request);
        }
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

    private static void injectInstructions(Map<String, Object> body, BasicParameters request) {
        List<IContent> messages = request.getMessages();
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (IContent message : messages) {
            if (message.isSystemInstruction()) {
                String instructions = message.instructionText();
                if (instructions != null && !instructions.isEmpty()) {
                    body.put("instructions", instructions);
                }
                return;
            }
        }
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
            Map<String, Object> text = new LinkedHashMap<>();
            text.put("verbosity", "low");
            body.put("text", text);
        }
    }
}
