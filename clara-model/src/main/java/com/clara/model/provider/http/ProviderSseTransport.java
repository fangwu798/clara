package com.clara.model.provider.http;

import com.clara.kernel.InvocationContext;

/**
 * 传输服务提供商SSE消息。
 */
public interface ProviderSseTransport {
    ProviderHttpResponse execute(ProviderHttpRequest request, InvocationContext context, ProviderSseListener listener);
}
