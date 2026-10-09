package com.clara.kernel.api.credential;


/**
 * 凭据解析契约：按配置里的凭据引用名取回调用密钥。
 *
 * <p>是什么、在哪一层：契约层（{@code com.clara.kernel.api.credential}）的 SPI。平台配置与能力配置只持有
 * 凭据引用名（{@code secretRef}），不持有密钥明文；只有组装请求头时才通过本接口把引用名换成密钥，因此日志
 * 与异常里不会出现密钥。当前实现是
 * {@link com.clara.kernel.credential.ProviderCredentialConfiguration}：它只按引用名匹配（环境变量或内联
 * 密钥），不按会话 / 租户切换密钥，多租户场景应由外部密钥服务实现本接口。
 *
 * <p>使用方法：由 {@code ProviderConfiguration#credentialResolver()} 取得实例，调用一次 {@link #resolve}
 * 拿到密钥副本，转成请求头后立即清零该数组。
 *
 * <p>失败语义：引用名不匹配或密钥缺失时返回 {@code null}，不抛异常；实现被释放（关闭）后再解析时抛
 * {@link IllegalStateException}——属调用时序问题，不可重试。
 */
public interface ICredentialResolver {
    /**
     * 按凭据引用名解析密钥。
     *
     * @param secretRef 配置中声明的凭据引用名，不可为 null、不可为空白
     * @return 密钥副本（调用方使用后应立即清零）；引用名不匹配、密钥缺失或环境变量为空时返回 {@code null}，
     *         不抛异常
     * @throws IllegalStateException 实现已被释放（内联密钥已清零）后调用时
     */
    char[] resolve(String secretRef);
}
