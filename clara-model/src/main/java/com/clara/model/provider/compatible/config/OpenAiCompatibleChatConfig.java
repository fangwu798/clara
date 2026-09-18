package com.clara.model.provider.compatible.config;

import com.clara.model.api.ModelProvider;
import com.clara.model.provider.compatible.OpenAiCompatibleStreamingChatModel;
import com.clara.model.provider.compatible.OpenAiCompatibleChatModel;
import com.clara.model.utils.StringUtils;
import lombok.Data;

import java.net.URI;

/**
 * 定义一次 OpenAI 兼容对话调用的固定配置。
 *
 * <p>用途：固化“用哪个平台、打到哪个端点、用哪个模型、使用哪份凭据”，供
 * {@link OpenAiCompatibleChatModel}（同步）与 {@link OpenAiCompatibleStreamingChatModel}（流式）在编码
 * 请求、校验响应时读取。实例不可变；凭据只保存引用名，真实密钥在每次调用时按租户解析。
 *
 * <p>使用方式（通常由平台配置派生，无需手工拼装）：
 * <pre>{@code
 * OpenAiCompatibleChatConfig config = provider.toOpenAiCompatibleChatConfig("chat");
 * ChatModel model = new OpenAiCompatibleChatModel(config, transport, provider.credentialResolver());
 * }</pre>
 *
 * <p>注意：仅支持采用 OpenAI 兼容协议的平台（OPENAI、DEEPSEEK、VOLCENGINE、ALIBABA），其它平台在构造时
 * 直接拒绝；{@code modelId}、{@code apiProfile}、{@code secretRef} 不可为空白；{@code apiProfile} 用于区分
 * 同一平台的不同调用变体。
 */
@Data
public final class OpenAiCompatibleChatConfig {
    /**
     * 所属平台，决定请求体的平台特有字段与响应元数据中的 provider 值。
     */
    private final ModelProvider provider;
    /**
     * 对话接口的绝对 URI，即请求最终打到的地址。
     */
    private final URI endpoint;
    /**
     * 注册的固定模型 ID，请求体中的 model 字段取自此处。
     */
    private final String modelId;
    /**
     * API 协议档位，用于区分同一平台的不同调用变体。
     */
    private final String apiProfile;
    /**
     * 凭据引用名，适配器只持有该引用，实际密钥在调用时解析。
     */
    private final String secretRef;

    /**
     * 创建对话配置并校验平台与必填字段。
     *
     * @param provider   平台，必须使用 OpenAI 兼容协议
     * @param endpoint   对话接口的绝对 URI，不可为 null
     * @param modelId    固定模型 ID，不可为空白
     * @param apiProfile API 协议档位，不可为空白
     * @param secretRef  凭据引用名，不可为空白
     * @throws IllegalArgumentException 平台不支持该协议，或端点、模型 ID、协议档位、凭据引用不合法时
     */
    public OpenAiCompatibleChatConfig(ModelProvider provider, URI endpoint, String modelId, String apiProfile, String secretRef) {
        if (provider != ModelProvider.OPENAI && provider != ModelProvider.DEEPSEEK && provider != ModelProvider.VOLCENGINE && provider != ModelProvider.ALIBABA) {
            throw new IllegalArgumentException("provider does not use the OpenAI-compatible profile");
        }
        if (endpoint == null) {
            throw new IllegalArgumentException("endpoint must not be null");
        }
        this.modelId = StringUtils.requireNotBlank(modelId, "modelId");
        this.apiProfile = StringUtils.requireNotBlank(apiProfile, "apiProfile");
        this.secretRef = StringUtils.requireNotBlank(secretRef, "secretRef");
        this.provider = provider;
        this.endpoint = endpoint;
    }
}
