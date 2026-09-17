package com.clara.model.provider.http;

/**
 * 解析并提供服务提供商的调用凭据。
 *
 * <p>用途：把「凭据引用名」与真实密钥解耦。模型适配器只在配置中持有 {@code secretRef}，
 * 每次发起请求时再通过本接口按租户解析出密钥明文，从而避免把密钥长期存放在适配器字段中。
 *
 * <p>使用规范：
 * <ul>
 *   <li>返回值是密钥副本，调用方使用完必须用 {@code Arrays.fill(secret, '\0')} 清零，
 *       且清理动作要放在 {@code finally} 中，避免异常路径泄漏明文。</li>
 *   <li>引用名不匹配或密钥缺失时返回 {@code null}，不要抛异常；调用方会统一转换为鉴权错误。</li>
 *   <li>实现必须是线程安全的：流式调用在工作线程中执行，同一实例可能被并发解析。</li>
 *   <li>单机实现可参考 {@link com.clara.model.configuration.ProviderCredentialConfiguration#resolver()}；
 *       多租户系统应由外部密钥服务实现本接口。</li>
 * </ul>
 */
public interface ProviderCredentialResolver {
    /**
     * 按租户与凭据引用名解析密钥。
     *
     * @param tenantId  租户标识，单机单租户实现可忽略
     * @param secretRef 配置中声明的凭据引用名
     * @return 密钥副本；引用名不匹配或密钥缺失时返回 {@code null}
     */
    char[] resolve(String tenantId, String secretRef);
}
