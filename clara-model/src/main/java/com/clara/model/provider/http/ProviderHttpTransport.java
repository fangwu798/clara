package com.clara.model.provider.http;

import com.clara.kernel.InvocationContext;

public interface ProviderHttpTransport {
    ProviderHttpResponse execute(ProviderHttpRequest request, InvocationContext context);
}
