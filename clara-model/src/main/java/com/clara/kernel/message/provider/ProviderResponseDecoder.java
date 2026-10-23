package com.clara.kernel.message.provider;

import com.clara.context.ConvContext;
import com.clara.enums.ProtocolEnum;
import com.clara.core.model.IResponse;
import com.clara.kernel.api.execute.IResponseDecoder;
import com.clara.kernel.config.ModelConfig;
import com.clara.core.transport.RawHttpResponse;
import com.clara.kernel.message.provider.completion.CompletionResponseDecoder;
import com.clara.kernel.message.provider.messages.MessagesResponseDecoder;
import com.clara.kernel.message.provider.response.ResponseResponseDecoder;

/**
 * 响应解码的协议分派入口：按当前能力的协议档位选择 Chat Completions 或 Responses 解码器。
 *
 * <p>保留本类作为静态入口，避免调用方（如 {@code IHttpExecute} / {@code StreamHandle}）感知协议实现差异；
 * 无状态、线程安全。
 */
public final class ProviderResponseDecoder {

    private static final IResponseDecoder COMPLETION = new CompletionResponseDecoder();
    private static final IResponseDecoder RESPONSE = new ResponseResponseDecoder();
    private static final IResponseDecoder MESSAGES = new MessagesResponseDecoder();

    private ProviderResponseDecoder() {
    }

    /**
     * 按协议分派同步解码。
     *
     * @param raw          原始 HTTP 响应，不可为 null
     * @param responseType 响应类型，不可为 null
     * @param <R>          响应类型，需实现 {@link IResponse}
     * @return 已填充的响应对象，非 null
     */
    public static <R extends IResponse> R decode(RawHttpResponse raw, Class<R> responseType) {
        return decoder().decode(raw, responseType);
    }

    /**
     * 按协议分派创建流式累加器。
     *
     * @param responseType 响应类型，不可为 null
     * @param <R>          响应类型，需实现 {@link IResponse}
     * @return 累加器，非 null
     */
    public static <R extends IResponse> StreamAssembler<R> streamAssembler(Class<R> responseType) {
        return decoder().streamAssembler(responseType);
    }

    private static IResponseDecoder decoder() {
        ConvContext context = ConvContext.current();
        ModelConfig config = context.getProviderConfiguration().toConfig(context.getCapabilityKind());
        ProtocolEnum protocol = config.getProtocol();
        if (protocol == ProtocolEnum.RESPONSE) {
            return RESPONSE;
        }
        if (protocol == ProtocolEnum.MESSAGES) {
            return MESSAGES;
        }
        return COMPLETION;
    }
}
