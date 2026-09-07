package com.clara.model.provider.media;

import com.clara.kernel.InvocationContext;
import com.clara.model.api.ModelProvider;
import com.clara.model.provider.http.ProviderCredentialResolver;
import com.clara.model.provider.http.ProviderHttpRequest;
import com.clara.model.provider.http.ProviderHttpResponse;
import com.clara.model.provider.http.ProviderHttpTransport;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;

/**
 * 封装服务提供商媒体接口的 HTTP 调用逻辑。
 */
public final class ProviderMediaHttp {
    private final ModelProvider provider;
    private final String secretRef;
    private final ProviderHttpTransport transport;
    private final ProviderCredentialResolver credentials;

    public ProviderMediaHttp(ModelProvider provider, String secretRef, ProviderHttpTransport transport, ProviderCredentialResolver credentials) {
        if (provider == null || transport == null || credentials == null) {
            throw new IllegalArgumentException("dependencies must not be null");
        }
        if (secretRef == null || secretRef.trim().isEmpty()) {
            throw new IllegalArgumentException("secretRef must not be blank");
        }
        this.provider = provider;
        this.secretRef = secretRef;
        this.transport = transport;
        this.credentials = credentials;
    }

    public ProviderHttpResponse execute(String method, URI endpoint, String contentType, byte[] body, InvocationContext context, Map<String, String> additionalHeaders) {
        context.getCancellationToken().throwIfCancellationRequested(context.getTraceId());
        char[] resolved = credentials.resolve(context.getTenantId(), secretRef);
        if (resolved == null || resolved.length == 0) { throw ProviderMediaErrors.credential(provider); }
        char[] secret = resolved.clone();
        try {
            Map<String, String> headers = new LinkedHashMap<String, String>();
            headers.put("Authorization", "Bearer " + new String(secret));
            if (contentType != null) headers.put("Content-Type", contentType);
            headers.put("Accept", "application/json");
            if (additionalHeaders != null) {
                for (Map.Entry<String, String> entry : additionalHeaders.entrySet()) {
                    String name = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
                    if ("authorization".equals(name) || "content-type".equals(name)) {
                        throw new IllegalArgumentException("reserved provider header");
                    }
                    headers.put(entry.getKey(), entry.getValue());
                }
            }
            ProviderHttpResponse response = transport.execute(new ProviderHttpRequest(method, endpoint, headers, body), context);
            if (response == null) { throw ProviderMediaErrors.protocol(provider); }
            if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
                throw ProviderMediaErrors.provider(provider, response.getStatusCode());
            }
            return response;
        } finally {
            Arrays.fill(secret, '\0');
        }
    }
}
