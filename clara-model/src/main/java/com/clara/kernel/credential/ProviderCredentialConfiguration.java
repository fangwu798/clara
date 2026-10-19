package com.clara.kernel.credential;


import com.clara.core.model.ICredentialResolver;
import com.clara.utils.StringUtils;
import com.clara.utils.deserialize.SecretCharsDeserializer;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Getter;

import java.io.Closeable;
import java.util.Arrays;

/**
 * 保存单个平台配置文件中的凭据来源，并按引用名生成凭据解析器。
 *
 * <p>常规用法：生产环境优先配置环境变量名；仅在受控的、已被 Git 忽略的本地 YAML 中才配置
 * {@code apiKey}。平台配置只持有 {@code secretRef}（引用名），组装请求头时再通过 {@link #resolver()} 解析密钥。
 *
 * <p>注意：环境变量与内联密钥必须且只能配置一种。本类适用于单机或单租户配置，
 * {@link #resolver()} 返回的解析器只按引用名匹配，不按会话上下文里的租户标识切换密钥；多租户系统应使用外部
 * 密钥服务实现 {@link ICredentialResolver}。使用结束后调用
 * {@link #close()} 清零内联密钥副本，但 JVM 中的 YAML 解析过程仍无法保证明文从未形成字符串对象。
 */
@Getter
public final class ProviderCredentialConfiguration implements Closeable {
    /**
     * 凭据引用名，平台配置只持有该引用。
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
     * 创建凭据配置，同时声明 YAML 形状（{@code @JsonCreator} + {@code @JsonProperty}）。
     *
     * <p>绑定走本构造器，因此「环境变量与内联密钥必须且只能配置一种」的校验在加载期生效。
     *
     * @param secretRef           凭据引用名，不可为空（YAML 键 {@code secretRef}，必填）
     * @param environmentVariable 环境变量名（YAML 键 {@code environmentVariable}，可选），与 apiKey 二选一
     * @param apiKey              内联密钥（YAML 键 {@code apiKey}，可选），与 environmentVariable 二选一；传入后会被克隆一份保存
     * @throws IllegalArgumentException secretRef 为空白，或环境变量与内联密钥同时配置、同时未配置时
     */
    @JsonCreator
    public ProviderCredentialConfiguration(
            @JsonProperty(value = "secretRef", required = true) String secretRef,
            @JsonProperty("environmentVariable") String environmentVariable,
            @JsonProperty("apiKey")
            @JsonDeserialize(using = SecretCharsDeserializer.class) char[] apiKey) {
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
     * 读取内联密钥副本；每次调用都返回新数组，调用方修改或清零不会影响本对象。
     *
     * @return 内联密钥副本；使用环境变量时返回 null
     */
    public char[] getApiKey() {
        return apiKey == null ? null : apiKey.clone();
    }


    /**
     * 创建凭据解析器。
     *
     * <p>返回的解析器忽略会话上下文中的租户标识，只按引用名匹配；解析出的密钥由调用方负责清零。解析器与本对象共享生命周期：本对象 {@link #close()} 之后再解析会抛 {@link IllegalStateException}。
     *
     * @return 仅在引用名匹配时返回密钥的解析器，非 null
     */
    public ICredentialResolver resolver() {
        return this::resolveSecret;
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
     * @return 密钥副本；引用不匹配或环境变量为空时返回零长度数组
     * @throws IllegalStateException 配置已关闭时
     */
    private synchronized char[] resolveSecret(String requestedSecretRef) {
        if (closed) {
            throw new IllegalStateException("credential configuration is closed");
        }
        if (!secretRef.equals(requestedSecretRef)) {
            return new char[0];
        }
        if (apiKey != null) {
            return apiKey.clone();
        }
        String value = System.getenv(environmentVariable);
        return StringUtils.isBlank(value) ? new char[0] : value.toCharArray();
    }

}
