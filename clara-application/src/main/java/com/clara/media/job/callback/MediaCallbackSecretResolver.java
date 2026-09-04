package com.clara.media.job.callback;

public interface MediaCallbackSecretResolver {
    byte[] resolve(String tenantId, String keyId);
}
