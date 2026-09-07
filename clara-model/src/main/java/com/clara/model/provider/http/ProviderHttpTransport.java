package com.clara.model.provider.http;

import com.clara.kernel.InvocationContext;

/**
 * 传输服务提供商HTTP消息。
 */
public interface ProviderHttpTransport {
    ProviderHttpResponse execute(ProviderHttpRequest request, InvocationContext context);
}
