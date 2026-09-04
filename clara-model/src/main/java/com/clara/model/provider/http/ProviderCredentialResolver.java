package com.clara.model.provider.http;

public interface ProviderCredentialResolver {
    char[] resolve(String tenantId, String secretRef);
}
