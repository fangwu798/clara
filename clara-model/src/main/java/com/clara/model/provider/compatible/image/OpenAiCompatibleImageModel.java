package com.clara.model.provider.compatible.image;

import com.clara.media.MediaType;
import com.clara.model.api.media.ImageGenerationModel;
import com.clara.model.api.media.ImageGenerationRequest;
import com.clara.model.api.media.ImageGenerationResult;
import com.clara.model.provider.http.ProviderCredentialResolver;
import com.clara.model.provider.http.ProviderHttpTransport;
import com.clara.model.provider.http.impl.ProviderInvoker;
import com.clara.model.provider.http.impl.ProviderHttpResponse;
import com.clara.model.provider.media.MultipartBody;
import com.clara.model.provider.media.ProviderMediaAssetBridge;
import com.clara.model.runtime.ProviderModelDescriptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提供 OpenAI 兼容图片生成能力。
 *
 * <p>用途：一个模型类覆盖所有采用 OpenAI 兼容图片协议的平台。本类只做「校验 → 编码 → 发送 → 解析 →
 * 落盘 → 组装结果」这条通用流程，两条编码路径（JSON 与 multipart 编辑）由
 * {@link OpenAiCompatibleImageOptions#usesMultipartEdit} 决定，平台专属字段与限制由该选项实现补充，
 * 因此协议包不需要认识任何具体平台。
 *
 * <p>流式生图见 {@link OpenAiCompatibleStreamingImageModel}：两者共用配置、平台选项、前置校验与协议
 * 编解码，差异只在「一次拿到全部结果」还是「按事件流逐张返回」。
 *
 * <p>使用方式（由平台配置派生）：
 * <pre>{@code
 * OpenAiCompatibleImageModel model = new OpenAiCompatibleImageModel(
 *         provider.toVolcengineImageConfig("image"), provider.toVolcengineImageOptions("image"),
 *         descriptor, assets, transport, provider.credentialResolver());
 * }</pre>
 *
 * <p>注意：
 * <ul>
 *   <li>构造时要求描述符的平台与模型 ID 和配置一致，避免把模型实例挂到错误的登记项上；</li>
 *   <li>提示词长度、生成数量、源图数量在发送前按配置上限校验，超限抛资源限制类错误；</li>
 *   <li>平台不支持的字段由选项的 {@code validate} 提前拒绝，不会发出半成品请求；</li>
 *   <li>响应条数必须等于请求数量，内联结果按 PNG 落盘、远程结果由资源层按 URL 拉取后落盘。</li>
 * </ul>
 */
public final class OpenAiCompatibleImageModel implements ImageGenerationModel {
    /**
     * 图片配置，提供平台、端点、模型 ID、凭据引用与本地限制。
     */
    private final OpenAiCompatibleImageConfig config;
    /**
     * 平台扩展点，负责平台专属字段、限制校验与编码路径选择。
     */
    private final OpenAiCompatibleImageOptions options;
    /**
     * 模型登记项，用于能力校验。
     */
    private final ProviderModelDescriptor descriptor;
    /**
     * 资源桥，负责把平台结果落盘并返回资产 ID。
     */
    private final ProviderMediaAssetBridge assets;
    /**
     * 统一的往返执行器，负责凭据、请求头、发送与状态码校验。
     */
    private final ProviderInvoker invoker;
    /**
     * 协议编解码器，只与配置的平台绑定。
     */
    private final OpenAiCompatibleImageCodec codec;

    /**
     * 创建兼容图片模型。
     *
     * @param config     图片配置，不可为 null
     * @param options    平台扩展点，不可为 null
     * @param descriptor 模型登记项，平台与模型 ID 必须与配置一致
     * @param assets     资源桥，不可为 null
     * @param transport  HTTP 传输器，不可为 null
     * @param credentials 凭据解析器，不可为 null
     * @throws IllegalArgumentException 依赖为 null 或描述符与配置不匹配时
     */
    public OpenAiCompatibleImageModel(OpenAiCompatibleImageConfig config, OpenAiCompatibleImageOptions options,
            ProviderModelDescriptor descriptor, ProviderMediaAssetBridge assets,
            ProviderHttpTransport transport, ProviderCredentialResolver credentials) {
        if (config == null || options == null || descriptor == null || assets == null) {
            throw new IllegalArgumentException("dependencies must not be null");
        }
        if (descriptor.getProvider() != config.getProvider()
                || !config.getModelId().equals(descriptor.getModelId())) {
            throw new IllegalArgumentException("descriptor does not match config");
        }
        this.config = config;
        this.options = options;
        this.descriptor = descriptor;
        this.assets = assets;
        this.invoker = new ProviderInvoker(config.getProvider(), config.getSecretRef(), transport, credentials);
        this.codec = new OpenAiCompatibleImageCodec(config);
    }

    public ImageGenerationResult generate(ImageGenerationRequest request) {
        OpenAiCompatibleImageValidator.validate(config, options, descriptor, request);
        OpenAiCompatibleImageCodec.Decoded decoded = codec.decode(send(request).getBody(), config, request.getCount());
        List<String> ids = new ArrayList<String>();
        for (OpenAiCompatibleImageCodec.Output output : decoded.getOutputs()) {
            ids.add(store(output, request));
        }
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("provider", config.getProvider().name());
        metadata.put("model", config.getModelId());
        return new ImageGenerationResult(ids, decoded.getRequestId(), metadata);
    }

    /**
     * 按选项选择的编码路径发送一次请求。
     *
     * @param request 图片请求
     * @return 平台响应
     * @throws com.clara.kernel.ClaraException 凭据不可用、传输失败或平台返回非 2xx 时
     */
    private ProviderHttpResponse send(ImageGenerationRequest request) {
        if (options.usesMultipartEdit(request)) {
            MultipartBody body = codec.encodeMultipart(request, config, assets);
            return invoker.execute("POST", config.getEndpoint(), body.contentType(), body.finish(),
                    null, request.getContext());
        }
        byte[] payload = codec.encodeJson(request, config, options, assets, false);
        return invoker.execute("POST", config.getEndpoint(), "application/json", payload,
                null, request.getContext());
    }

    /**
     * 把一条输出落盘并返回资产 ID。
     *
     * @param output  输出，可能是内联字节或远程 URL
     * @param request 图片请求，提供调用上下文
     * @return 资产 ID
     */
    private String store(OpenAiCompatibleImageCodec.Output output, ImageGenerationRequest request) {
        return output.isInline()
                ? assets.storeBytes(output.getBytes(), MediaType.IMAGE, output.getMimeType(), request.getContext())
                : assets.storeRemote(output.getUrl(), MediaType.IMAGE, output.getMimeType(), request.getContext());
    }
}
