package com.clara.model.provider.http;

import com.clara.kernel.InvocationContext;

public interface ProviderSseTransport {
    ProviderHttpResponse execute(ProviderHttpRequest request, InvocationContext context,
            ProviderSseListener listener);
}
