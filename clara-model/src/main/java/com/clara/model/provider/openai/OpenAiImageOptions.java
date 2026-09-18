package com.clara.model.provider.openai;

import com.clara.model.api.ModelProvider;
import com.clara.model.api.media.ImageGenerationRequest;
import com.clara.model.provider.compatible.image.OpenAiCompatibleImageOptions;
import com.clara.model.provider.media.MultipartBody;
import com.clara.model.provider.media.ProviderMediaAssetBridge;
import com.clara.model.utils.ExceptionUtils;

import java.util.Map;

/**
 * OpenAI 图片接口的平台扩展点。
 *
 * <p>用途：声明 OpenAI 图片接口相对兼容骨架的差异——默认以内联 Base64 返回结果、支持
 * {@code quality}/{@code style}，带源图的请求走 multipart 编辑上传（{@code /images/edits}），不支持比例、
 * 种子与负向提示词。
 *
 * <p>使用方式：本实现无可变状态，直接 {@code new OpenAiImageOptions()} 即可，可安全共享。
 *
 * <p>注意：编辑上传要求配置中给出正的 {@code maxInputBytes}，否则编码阶段会直接拒绝。
 */
public final class OpenAiImageOptions implements OpenAiCompatibleImageOptions {
    public void applyToJson(ImageGenerationRequest request, Map<String, Object> body,
            ProviderMediaAssetBridge assets) {
        body.put("response_format", "b64_json");
        if (request.getQuality() != null) {
            body.put("quality", request.getQuality());
        }
        if (request.getStyle() != null) {
            body.put("style", request.getStyle());
        }
    }

    public void applyToMultipart(ImageGenerationRequest request, MultipartBody body) {
        if (request.getQuality() != null) {
            body.text("quality", request.getQuality());
        }
    }

    public boolean usesMultipartEdit(ImageGenerationRequest request) {
        return request.isEdit();
    }

    public void validate(ImageGenerationRequest request) {
        if (request.getAspectRatio() != null || request.getSeed() != null
                || request.getNegativePrompt() != null) {
            throw ExceptionUtils.mediaTransportUnsupported(ModelProvider.OPENAI);
        }
    }
}
