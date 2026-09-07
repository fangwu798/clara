package com.clara.media.job.callback;

/**
 * 解析并提供媒体回调密钥。
 */
public interface MediaCallbackSecretResolver {
    byte[] resolve(String tenantId, String keyId);
}
