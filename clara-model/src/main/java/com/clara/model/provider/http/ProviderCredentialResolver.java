package com.clara.model.provider.http;

/**
 * 解析并提供服务提供商凭据。
 */
public interface ProviderCredentialResolver {
    char[] resolve(String tenantId, String secretRef);
}
