package com.clara.model.configuration;

import com.clara.model.provider.http.ProviderCredentialResolver;
import com.clara.model.utils.StringUtils;
import lombok.Data;

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
@Data
public final class ProviderCredentialConfiguration implements Closeable {
    /**
     * 凭据引用名，模型适配器只持有该引用。
     */
    private final String secretRef;
    /**
     * 环境变量名，与内联密钥二选一。
     */
    private final String environmentVariable;
    /**
     * 内联密钥副本，与环境变量二选一，关闭时会清零。
     */
    private final char[] apiKey;
    /**
     * 配置是否已关闭。
     */
    private boolean closed;

    /**
     * 创建凭据配置。
     *
     * @param secretRef           凭据引用名，不可为空
     * @param environmentVariable 环境变量名，与 apiKey 二选一
     * @param apiKey              内联密钥，与 environmentVariable 二选一
     * @throws IllegalArgumentException secretRef 为空，或环境变量与内联密钥未配置且仅配置一种时
     */
    public ProviderCredentialConfiguration(String secretRef, String environmentVariable, char[] apiKey) {
        this.secretRef = StringUtils.requireNotBlank(secretRef, "secretRef");
        boolean hasEnvironment = !StringUtils.isBlank(environmentVariable);
        boolean hasApiKey = apiKey != null && apiKey.length > 0;
        if (hasEnvironment == hasApiKey) {
            throw new IllegalArgumentException("exactly one of environmentVariable and apiKey must be configured");
        }
        this.environmentVariable = hasEnvironment ? environmentVariable : null;
        this.apiKey = hasApiKey ? apiKey.clone() : null;
    }


    /**
     * 创建凭据解析器。
     *
     * @return 仅在引用名匹配时返回密钥的解析器
     */
    public ProviderCredentialResolver resolver() {
        return (tenantId, requestedSecretRef) -> resolveSecret(requestedSecretRef);
    }

    /**
     * 关闭并清零内联密钥副本，重复调用无副作用。
     */
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

    /**
     * 按引用名解析密钥。
     *
     * @param requestedSecretRef 请求的凭据引用名
     * @return 密钥副本，引用不匹配或环境变量为空时返回 null
     * @throws IllegalStateException 配置已关闭时
     */
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
        return StringUtils.isBlank(value) ? null : value.toCharArray();
    }

}
