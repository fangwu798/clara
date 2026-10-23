package com.clara.kernel.message.provider.messages;

import com.clara.core.annotation.ProtocolResponse;
import com.clara.core.model.IResponse;
import com.clara.enums.ModelProviderEnum;
import com.clara.enums.ProtocolEnum;
import com.clara.kernel.message.provider.AbstractResponseDecoder;
import com.clara.kernel.message.provider.StreamAssembler;
import com.clara.utils.deserialize.ResponseDecoderSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Anthropic Messages 协议的响应解码器。
 *
 * <p>同步响应按 {@code content[]} 的块类型取值：正文取 {@code text} 块、思维链取 {@code thinking} 块、
 * 工具调用取 {@code tool_use} 块；结束原因取 {@code stop_reason}，用量取 {@code usage.*}。
 * 流式累加用 {@link MessagesStreamAssembler}。
 */
public final class MessagesResponseDecoder extends AbstractResponseDecoder {

    @Override
    protected ProtocolResponse protocolResponse(Field field) {
        for (ProtocolResponse protocolResponse : field.getDeclaredAnnotationsByType(ProtocolResponse.class)) {
            if (protocolResponse.protocol() == ProtocolEnum.MESSAGES) {
                return protocolResponse;
            }
        }
        return null;
    }

    @Override
    protected Object convertField(Field field, JsonNode node, ModelProviderEnum provider) {
        if (MessagesToolCalls.isToolCallList(field)) {
            return MessagesToolCalls.fromContent(node);
        }
        return ResponseDecoderSupport.convert(field.getGenericType(), node, provider);
    }

    @Override
    protected <R extends IResponse> StreamAssembler<R> createStreamAssembler(R target, List<StreamAssembler.Rule> deltas,
            List<StreamAssembler.Rule> singles, List<StreamAssembler.Rule> terminals, ModelProviderEnum provider) {
        return new MessagesStreamAssembler<>(target, deltas, singles, terminals, provider);
    }
}
