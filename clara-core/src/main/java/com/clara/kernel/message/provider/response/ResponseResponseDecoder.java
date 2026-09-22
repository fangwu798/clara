package com.clara.utils.deserialize;

import com.clara.annoation.ProtocolResponse;
import com.clara.enums.ModelProviderEnum;
import com.clara.enums.ProtocolEnum;
import com.clara.kernel.api.IResponse;
import com.fasterxml.jackson.databind.JsonNode;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Responses 协议的响应解码器。
 *
 * <p>用 {@link ProtocolResponse} 覆盖字段取值路径，工具调用字段按独立类型 {@code ResponsesToolCall} 过滤转换，
 * 流式累加用 {@link ResponseStreamAssembler}。
 */
public final class ResponseResponseDecoder extends AbstractResponseDecoder {

    @Override
    protected ProtocolResponse protocolResponse(Field field) {
        for (ProtocolResponse protocolResponse : field.getDeclaredAnnotationsByType(ProtocolResponse.class)) {
            if (protocolResponse.protocol() == ProtocolEnum.RESPONSE) {
                return protocolResponse;
            }
        }
        return null;
    }

    @Override
    protected Object convertField(Field field, JsonNode node, ModelProviderEnum provider) {
        if (ResponseToolCallSupport.isResponsesToolCallList(field)) {
            return ResponseToolCallSupport.convertResponsesToolCalls(node);
        }
        return ResponseDecoderSupport.convert(field.getGenericType(), node, provider);
    }

    @Override
    protected <R extends IResponse> StreamAssembler<R> createStreamAssembler(R target, List<StreamAssembler.Rule> deltas, List<StreamAssembler.Rule> singles, List<StreamAssembler.Rule> terminals, ModelProviderEnum provider) {
        return new ResponseStreamAssembler<R>(target, deltas, singles, terminals, provider);
    }
}
