package com.clara.kernel.message.provider.completion;

import com.clara.enums.ModelProviderEnum;
import com.clara.enums.ThinkingEnum;
import com.clara.core.model.IRequest;
import com.clara.core.message.IContent;
import com.clara.kernel.message.BasicParameters;
import com.clara.kernel.message.delta.Thinking;
import com.clara.kernel.message.provider.AbstractRequestEncoder;
import com.clara.dto.tool.ToolContext;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Chat Completions 协议的请求编码器。
 *
 * <p>字段名沿用 {@code @JsonProperty} / 字段名，字段值不做结构转换；唯一需要额外处理的是阿里云百炼：
 * 千问用布尔字段 {@code enable_thinking} 控制思考模式，而 Clara 统一模型是 {@code thinking.type}，
 * 因此在 {@link #postProcessBody} 中做一次语义转换，避免把平台不认识的 {@code thinking} 对象下发过去。
 */
public final class CompletionRequestEncoder extends AbstractRequestEncoder {

    @Override
    protected String protocolName(Field field, String baseName) {
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
            List<Map<String, Object>> messages = new ArrayList<>();
            for (Object o : list) {
                messages.add(((IContent) o).toCompletionMessage());
            }
            return messages;
        }
        if (first instanceof ToolContext) {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (Object o : list) {
                tools.add(((ToolContext) o).toCompletionTool());
            }
            return tools;
        }
        return value;
    }

    @Override
    protected void postProcessBody(Map<String, Object> body, IRequest request, ModelProviderEnum provider, Map<String, String> authorization) {
        if (provider == ModelProviderEnum.ALIBABA) {
            applyAlibabaThinking(body, request);
        }
    }

    /**
     * 把 Clara 的 {@code thinking.type} 转成阿里云百炼的 {@code enable_thinking} 布尔字段。
     *
     * <p>官方深度思考文档给出的线格式是 {@code "enable_thinking": true/false}；
     * {@code auto} 没有对应取值，直接省略、沿用平台默认（qwen3.8-max 默认开启思考）。
     *
     * @param body    已收敛的请求体，非 null
     * @param request 原始请求，非 null
     */
    private static void applyAlibabaThinking(Map<String, Object> body, IRequest request) {
        if (!(request instanceof BasicParameters)) {
            return;
        }
        Thinking thinking = ((BasicParameters) request).getThinking();
        if (thinking == null || thinking.getType() == null || thinking.getType() == ThinkingEnum.AUTO) {
            return;
        }
        body.put("enable_thinking", thinking.getType() == ThinkingEnum.ENABLED);
    }
}
