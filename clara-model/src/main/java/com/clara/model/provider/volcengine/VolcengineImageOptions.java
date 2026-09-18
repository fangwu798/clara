package com.clara.model.provider.volcengine;

import com.clara.media.MediaType;
import com.clara.model.api.ModelProvider;
import com.clara.model.api.media.ImageGenerationRequest;
import com.clara.model.provider.compatible.image.OpenAiCompatibleImageOptions;
import com.clara.model.provider.media.ProviderMediaAssetBridge;
import com.clara.model.utils.ExceptionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 火山引擎（方舟）图片接口的平台扩展点。
 *
 * <p>用途：把方舟相对 OpenAI 图片协议的差异从兼容骨架里剥离出来——方舟要求显式声明
 * {@code response_format=url}、默认带水印、支持 {@code seed}/{@code negative_prompt}，图生图则以
 * {@code image} 字段直接下发图片地址（不走 multipart 上传），且不支持蒙版编辑。
 *
 * <p>使用方式（由平台配置派生）：
 * <pre>{@code
 * OpenAiCompatibleImageOptions options = new VolcengineImageOptions(watermark);
 * }</pre>
 *
 * <p>注意：实例不可变且线程安全，{@code watermark} 取自媒体能力参数，决定平台是否在结果上加水印。
 */
public final class VolcengineImageOptions implements OpenAiCompatibleImageOptions {
    /**
     * 是否让平台在生成结果上添加水印。
     */
    private final boolean watermark;

    /**
     * 创建方舟图片选项。
     *
     * @param watermark 是否添加水印
     */
    public VolcengineImageOptions(boolean watermark) {
        this.watermark = watermark;
    }

    public void applyToJson(ImageGenerationRequest request, Map<String, Object> body, ProviderMediaAssetBridge assets) {
        body.put("response_format", "url");
        body.put("watermark", Boolean.valueOf(watermark));
        if (request.getSeed() != null) {
            body.put("seed", request.getSeed());
        }
        if (request.getNegativePrompt() != null) {
            body.put("negative_prompt", request.getNegativePrompt());
        }
        List<String> references = new ArrayList<String>();
        for (String assetId : request.getSourceImageAssetIds()) {
            references.add(assets.inputReference(assetId, MediaType.IMAGE, request.getContext()));
        }
        if (references.size() == 1) {
            body.put("image", references.get(0));
        } else if (!references.isEmpty()) {
            body.put("image", references);
        }
    }

    public void validate(ImageGenerationRequest request) {
        if (request.getMaskAssetId() != null) {
            throw ExceptionUtils.mediaTransportUnsupported(ModelProvider.VOLCENGINE);
        }
    }
}
