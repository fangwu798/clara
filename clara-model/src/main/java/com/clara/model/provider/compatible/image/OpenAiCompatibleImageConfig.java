package com.clara.model.provider.compatible.image;

import com.clara.model.api.ModelProvider;
import com.clara.model.utils.StringUtils;
import lombok.Data;

import java.net.URI;

/**
 * 定义一次 OpenAI 兼容图片生成调用的固定配置。
 *
 * <p>用途：固化“用哪个平台、打到哪个端点、用哪个模型、使用哪份凭据、本地限制是多少”，供
 * {@link OpenAiCompatibleImageModel} 在校验请求、编码请求体与解析响应时读取。实例不可变；凭据只保存
 * 引用名，真实密钥在每次调用时按租户解析。
 *
 * <p>使用方式（通常由平台配置派生，无需手工拼装）：
 * <pre>{@code
 * OpenAiCompatibleImageConfig config = provider.toVolcengineImageConfig("image");
 * ImageGenerationModel model = new OpenAiCompatibleImageModel(config, options, descriptor, assets, transport,
 *         provider.credentialResolver());
 * }</pre>
 *
 * <p>注意：仅支持采用 OpenAI 兼容图片协议的平台（OPENAI、VOLCENGINE），其它平台在构造时直接拒绝；
 * {@code modelId}、{@code secretRef} 不可为空白；{@code maxInputBytes} 只在 multipart 编辑路径下生效，
 * 为 0 表示该平台不通过二进制上传源图（例如火山以 JSON 传源图 URL）。
 */
@Data
public final class OpenAiCompatibleImageConfig {
    /**
     * 所属平台，决定错误归因与响应元数据中的 provider 值。
     */
    private final ModelProvider provider;
    /**
     * 图片接口的绝对 URI，即请求最终打到的地址。
     */
    private final URI endpoint;
    /**
     * 注册的固定模型 ID，请求体中的 model 字段取自此处。
     */
    private final String modelId;
    /**
     * 凭据引用名，适配器只持有该引用，实际密钥在调用时解析。
     */
    private final String secretRef;
    /**
     * 提示词允许的最大字符数。
     */
    private final int maxPromptChars;
    /**
     * 单次请求允许生成的最大图片数量。
     */
    private final int maxOutputs;
    /**
     * 单次请求允许携带的最大源图数量。
     */
    private final int maxSourceImages;
    /**
     * multipart 编辑路径读取单张源图的最大字节数，0 表示该平台不走该路径。
     */
    private final int maxInputBytes;

    /**
     * 创建图片配置并校验平台与必填字段。
     *
     * @param provider        平台，必须使用 OpenAI 兼容图片协议
     * @param endpoint        图片接口的绝对 URI，不可为 null
     * @param modelId         固定模型 ID，不可为空白
     * @param secretRef       凭据引用名，不可为空白
     * @param maxPromptChars  提示词最大字符数，必须为正数
     * @param maxOutputs      单次生成数量上限，必须为正数
     * @param maxSourceImages 源图数量上限，可为 0（表示不支持源图）
     * @param maxInputBytes   单张源图字节上限，可为 0（表示不使用 multipart 编辑路径）
     * @throws IllegalArgumentException 平台不支持该协议，或字段、限制不合法时
     */
    public OpenAiCompatibleImageConfig(ModelProvider provider, URI endpoint, String modelId, String secretRef,
            int maxPromptChars, int maxOutputs, int maxSourceImages, int maxInputBytes) {
        if (provider != ModelProvider.OPENAI && provider != ModelProvider.VOLCENGINE) {
            throw new IllegalArgumentException("provider does not use the OpenAI-compatible image profile");
        }
        if (endpoint == null) {
            throw new IllegalArgumentException("endpoint must not be null");
        }
        if (maxPromptChars <= 0 || maxOutputs <= 0 || maxSourceImages < 0 || maxInputBytes < 0) {
            throw new IllegalArgumentException("image limits are invalid");
        }
        this.provider = provider;
        this.endpoint = endpoint;
        this.modelId = StringUtils.requireNotBlank(modelId, "modelId");
        this.secretRef = StringUtils.requireNotBlank(secretRef, "secretRef");
        this.maxPromptChars = maxPromptChars;
        this.maxOutputs = maxOutputs;
        this.maxSourceImages = maxSourceImages;
        this.maxInputBytes = maxInputBytes;
    }
}
