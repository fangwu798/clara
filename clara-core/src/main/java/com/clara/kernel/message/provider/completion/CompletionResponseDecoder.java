package com.clara.kernel.message.provider.completion;

import com.clara.annoation.ProtocolResponse;
import com.clara.enums.ModelProviderEnum;
import com.clara.kernel.api.IResponse;
import com.clara.kernel.message.provider.AbstractResponseDecoder;
import com.clara.kernel.message.provider.StreamAssembler;
import com.clara.utils.deserialize.ResponseDecoderSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Chat Completions 协议的响应解码器。
 *
 * <p>不做协议路径覆盖（{@code protocolResponse} 恒为 null），字段值按通用规则转换，流式累加用
 * {@link CompletionStreamAssembler}。
 */
public final class CompletionResponseDecoder extends AbstractResponseDecoder {

    @Override
    protected ProtocolResponse protocolResponse(Field field) {
        return null;
    }

    @Override
    protected Object convertField(Field field, JsonNode node, ModelProviderEnum provider) {
        return ResponseDecoderSupport.convert(field.getGenericType(), node, provider);
    }

    @Override
    protected <R extends IResponse> StreamAssembler<R> createStreamAssembler(R target, List<StreamAssembler.Rule> deltas, List<StreamAssembler.Rule> singles, List<StreamAssembler.Rule> terminals, ModelProviderEnum provider) {
        return new CompletionStreamAssembler<R>(target, deltas, singles, terminals, provider);
    }
}
