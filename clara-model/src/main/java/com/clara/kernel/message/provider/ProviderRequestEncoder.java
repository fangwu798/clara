package com.clara.kernel.message.provider;

import com.clara.context.ConvContext;
import com.clara.enums.ProtocolEnum;
import com.clara.core.model.IRequest;
import com.clara.kernel.api.execute.IRequestEncoder;
import com.clara.kernel.config.ModelConfig;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.dto.model.RawHttpRequest;
import com.clara.kernel.message.provider.completion.CompletionRequestEncoder;
import com.clara.kernel.message.provider.response.ResponseRequestEncoder;

/**
 * 请求编码的协议分派入口：按当前能力的协议档位选择 Chat Completions 或 Responses 编码器。
 *
 * <p>保留本类作为静态入口，避免调用方（如 {@code HttpRequest#toRequest()}）感知协议实现差异；无状态、线程安全。
 */
public final class ProviderRequestEncoder {

    private static final IRequestEncoder COMPLETION = new CompletionRequestEncoder();
    private static final IRequestEncoder RESPONSE = new ResponseRequestEncoder();

    private ProviderRequestEncoder() {
    }

    /**
     * 按协议分派编码一次能力调用。
     *
     * @param request               请求对象，不可为 null
     * @param providerConfiguration 平台配置，提供平台、端点与协议，不可为 null
     * @return 可直接交给传输实现的 HTTP 请求，非 null
     */
    public static RawHttpRequest encode(IRequest request, ProviderConfiguration providerConfiguration) {
        ModelConfig config = providerConfiguration.toConfig(ConvContext.current().getCapabilityKind());
        IRequestEncoder encoder = config.getProtocol() == ProtocolEnum.RESPONSE ? RESPONSE : COMPLETION;
        return encoder.encode(request, providerConfiguration);
    }
}
