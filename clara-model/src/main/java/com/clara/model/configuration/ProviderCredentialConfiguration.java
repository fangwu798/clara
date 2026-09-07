package com.clara.model.configuration;

import com.clara.model.provider.http.ProviderCredentialResolver;
import java.io.Closeable;
import java.util.Arrays;

/**
 * 保存单个平台配置文件中的凭据来源，并生成 Clara 凭据解析器。
 *
 * <p>常规用法：生产环境优先配置环境变量名；仅在受控的、已被 Git 忽略的本地 YAML 中才配置
 * {@code apiKey}。模型适配器只持有 {@code secretRef}，调用时再通过 {@link #resolver()} 解析密钥。
 *
 * <p>注意：环境变量与内联密钥必须且只能配置一种。本类适用于单机或单租户配置，解析时不会根据租户
 * 切换密钥；多租户系统应使用外部密钥服务实现 {@link ProviderCredentialResolver}。使用结束后调用
 * {@link #close()} 清零内联密钥副本，但 JVM 中的 YAML 解析过程仍无法保证明文从未形成字符串对象。
 */
public final class ProviderCredentialConfiguration implements Closeable {
    private final String secretRef;
    private final String environmentVariable;
    private final char[] apiKey;
    private boolean closed;

    public ProviderCredentialConfiguration(String secretRef, String environmentVariable,
            char[] apiKey) {
        this.secretRef = required(secretRef, "secretRef");
        boolean hasEnvironment = !isBlank(environmentVariable);
        boolean hasApiKey = apiKey != null && apiKey.length > 0;
        if (hasEnvironment == hasApiKey) {
            throw new IllegalArgumentException(
                    "exactly one of environmentVariable and apiKey must be configured");
        }
        this.environmentVariable = hasEnvironment ? environmentVariable : null;
        this.apiKey = hasApiKey ? apiKey.clone() : null;
    }

    public String getSecretRef() {
        return secretRef;
    }

    public String getEnvironmentVariable() {
        return environmentVariable;
    }

    public ProviderCredentialResolver resolver() {
        return new ProviderCredentialResolver() {
            @Override
            public char[] resolve(String tenantId, String requestedSecretRef) {
                return resolveSecret(requestedSecretRef);
            }
        };
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (apiKey != null) {
            Arrays.fill(apiKey, '\0');
        }
    }

    private synchronized char[] resolveSecret(String requestedSecretRef) {
        if (closed) {
            throw new IllegalStateException("credential configuration is closed");
        }
        if (!secretRef.equals(requestedSecretRef)) {
            return null;
        }
        if (apiKey != null) {
            return apiKey.clone();
        }
        String value = System.getenv(environmentVariable);
        return isBlank(value) ? null : value.toCharArray();
    }

    private static String required(String value, String name) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
