package com.clara.kernel.message.provider.completion;

import com.clara.enums.ModelProviderEnum;
import com.clara.core.model.IRequest;
import com.clara.core.message.IContent;
import com.clara.kernel.message.provider.AbstractRequestEncoder;
import com.clara.dto.tool.ToolContext;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
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
        // Chat Completions 无补充字段
    }
}
