package com.clara.kernel.message.provider;

import com.clara.core.annotation.ProtocolResponse;
import com.clara.core.annotation.ResponseAdapter;
import com.clara.context.ConvContext;
import com.clara.enums.CapabilityKindEnum;
import com.clara.enums.ModelProviderEnum;
import com.clara.core.model.IResponse;
import com.clara.kernel.api.execute.IResponseDecoder;
import com.clara.core.transport.RawHttpResponse;
import com.clara.utils.ExceptionUtils;
import com.clara.utils.deserialize.ResponseDecoderSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 响应解码器的抽象基类：承载同步解码与流式规则构建的协议无关逻辑，把协议差异留给子类覆盖
 * （{@link #protocolResponse} 提供协议路径覆盖、{@link #convertField} 提供字段转换、{@link #createStreamAssembler}
 * 提供流式累加器）。
 */
public abstract class AbstractResponseDecoder implements IResponseDecoder {

    protected AbstractResponseDecoder() {
    }

    @Override
    public <R extends IResponse> R decode(RawHttpResponse raw, Class<R> responseType) {
        if (raw == null || responseType == null) {
            throw new IllegalArgumentException("values must not be null");
        }
        ModelProviderEnum provider = provider();
        CapabilityKindEnum capability = capability();
        R target = ResponseDecoderSupport.newInstance(responseType);
        attachRaw(target, raw);
        if (raw.getBody().length == 0) {
            return target;
        }
        JsonNode root = ResponseDecoderSupport.parse(new String(raw.getBody(), StandardCharsets.UTF_8), provider);
        for (Field field : ResponseDecoderSupport.fields(responseType)) {
            decodeField(field, root, target, provider, capability);
        }
        return target;
    }

    private <R extends IResponse> void decodeField(Field field, JsonNode root, R target,
            ModelProviderEnum provider, CapabilityKindEnum capability) {
        ResponseAdapter adapter = ResponseDecoderSupport.match(field, provider, capability);
        if (adapter == null) {
            return;
        }
        ProtocolResponse protocolResponse = protocolResponse(field);
        String path = protocolResponse == null || protocolResponse.path().isEmpty()
                ? adapter.path() : protocolResponse.path();
        if (path.isEmpty()) {
            return;
        }
        JsonNode node = ResponseDecoderSupport.valueAt(root, path);
        if (ResponseDecoderSupport.present(node)) {
            ResponseDecoderSupport.write(field, target, convertField(field, node, provider));
        } else if (adapter.required()) {
            throw ExceptionUtils.protocolError(provider);
        }
    }

    @Override
    public <R extends IResponse> StreamAssembler<R> streamAssembler(Class<R> responseType) {
        if (responseType == null) {
            throw new IllegalArgumentException("responseType must not be null");
        }
        ModelProviderEnum provider = provider();
        CapabilityKindEnum capability = capability();
        List<StreamAssembler.Rule> deltas = new ArrayList<>();
        List<StreamAssembler.Rule> singles = new ArrayList<>();
        List<StreamAssembler.Rule> terminals = new ArrayList<>();
        for (Field field : ResponseDecoderSupport.fields(responseType)) {
            ResponseAdapter adapter = ResponseDecoderSupport.match(field, provider, capability);
            if (adapter != null) {
                addRule(field, adapter, deltas, singles, terminals);
            }
        }
        return createStreamAssembler(ResponseDecoderSupport.newInstance(responseType), deltas, singles, terminals, provider);
    }

    private void addRule(Field field, ResponseAdapter adapter, List<StreamAssembler.Rule> deltas,
            List<StreamAssembler.Rule> singles, List<StreamAssembler.Rule> terminals) {
        ProtocolResponse protocolResponse = protocolResponse(field);
        String path = pathOf(adapter, protocolResponse);
        String streamPath = streamPathOf(adapter, protocolResponse);
        String streamEvent = protocolResponse == null ? "" : protocolResponse.streamEvent();
        String terminalPath = protocolResponse == null || protocolResponse.terminalPath().isEmpty()
                ? adapter.terminalPath() : protocolResponse.terminalPath();
        StreamAssembler.Rule rule = new StreamAssembler.Rule(field, adapter, path, streamPath, streamEvent, terminalPath);
        if (!streamPath.isEmpty()) {
            deltas.add(rule);
        } else if (!path.isEmpty()) {
            singles.add(rule);
        }
        if (!terminalPath.isEmpty()) {
            terminals.add(rule);
        }
    }

    private static String pathOf(ResponseAdapter adapter, ProtocolResponse protocolResponse) {
        return protocolResponse == null || protocolResponse.path().isEmpty()
                ? adapter.path() : protocolResponse.path();
    }

    private static String streamPathOf(ResponseAdapter adapter, ProtocolResponse protocolResponse) {
        if (protocolResponse != null && protocolResponse.streamDisabled()) {
            return "";
        }
        return protocolResponse == null || protocolResponse.streamPath().isEmpty()
                ? adapter.streamPath() : protocolResponse.streamPath();
    }

    /**
     * 该协议下字段对应的 {@link ProtocolResponse} 覆盖规则；无覆盖时返回 null。
     */
    protected abstract ProtocolResponse protocolResponse(Field field);

    /**
     * 该协议下字段值的转换（如 Responses 的工具调用过滤）。
     */
    protected abstract Object convertField(Field field, JsonNode node, ModelProviderEnum provider);

    /**
     * 该协议下的流式累加器。
     */
    protected abstract <R extends IResponse> StreamAssembler<R> createStreamAssembler(R target,
            List<StreamAssembler.Rule> deltas, List<StreamAssembler.Rule> singles, List<StreamAssembler.Rule> terminals,
            ModelProviderEnum provider);

    private static void attachRaw(Object target, RawHttpResponse raw) {
        for (Field field : ResponseDecoderSupport.fields(target.getClass())) {
            if (RawHttpResponse.class.equals(field.getType())) {
                ResponseDecoderSupport.write(field, target, raw);
            }
        }
    }

    private static ModelProviderEnum provider() {
        ConvContext context = ConvContext.current();
        if (context == null || context.getProviderConfiguration() == null) {
            throw new IllegalStateException("provider configuration is required to decode a response");
        }
        return context.getProviderConfiguration().getProvider();
    }

    private static CapabilityKindEnum capability() {
        ConvContext context = ConvContext.current();
        if (context == null || context.getCapabilityKind() == null) {
            throw new IllegalStateException("capability kind is required to decode a response");
        }
        return context.getCapabilityKind();
    }
}
