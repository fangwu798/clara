package com.clara.enums;

/**
 * 模型服务提供商：一次模型调用落到哪家平台。
 *
 * <p>是什么、在哪一层：内核共享枚举包（{@code com.clara.enums}）中的值类型，既是平台级配置
 * （{@code ProviderConfiguration}，YAML 键 {@code provider}）的取值类型，也是适配注解
 * （{@code @ProviderAdapter} / {@code @ModelProvider}）声明平台的取值类型。
 */
public enum ModelProviderEnum {
    /** OpenAI：OpenAI 兼容协议家族成员，鉴权头为 {@code Authorization: Bearer <密钥>}。 */
    OPENAI,
    /** 火山方舟：OpenAI 兼容协议家族成员，鉴权头同 {@code Authorization: Bearer <密钥>}。 */
    VOLCENGINE,
    /** DeepSeek：OpenAI 兼容协议家族成员，鉴权头同 {@code Authorization: Bearer <密钥>}。 */
    DEEPSEEK,
    /** 阿里通义：OpenAI 兼容协议家族成员，鉴权头同 {@code Authorization: Bearer <密钥>}。 */
    ALIBABA,
    /** Anthropic：不走兼容家族，鉴权头为 {@code x-api-key} 加能力参数 {@code apiVersion}。 */
    ANTHROPIC
}
