package com.clara.kernel.api.credential;


/**
 * 解析并提供服务提供商的调用凭据。
 */
public interface ICredentialResolver {
    /**
     * 按会话与凭据引用名解析密钥。
     *
     * @param secretRef    配置中声明的凭据引用名，非 null、非空白
     * @return 密钥副本，永不为 null 的数组在调用方使用后会被清零；引用名不匹配或密钥缺失时返回
     *         {@code null}（不抛异常）
     */
    char[] resolve(String secretRef);
}
