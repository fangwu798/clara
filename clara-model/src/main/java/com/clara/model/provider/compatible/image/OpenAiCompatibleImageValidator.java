package com.clara.model.provider.compatible.image;

import com.clara.model.api.media.ImageGenerationRequest;
import com.clara.model.runtime.ModelCapabilityGuard;
import com.clara.model.runtime.ProviderModelDescriptor;
import com.clara.model.utils.ExceptionUtils;

/**
 * 校验一次图片请求是否满足模型能力、本地限制与平台支持范围。
 *
 * <p>用途：非流式 {@link OpenAiCompatibleImageModel} 与流式 {@link OpenAiCompatibleStreamingImageModel}
 * 必须在发送前做同一套校验——能力约束、平台归属、提示词长度/生成数量/源图数量上限，以及平台选项自己拒绝的
 * 字段。把校验收敛到这里，避免两条链路各写一份而逐渐漂移。
 *
 * <p>注意：本类只读请求、不做任何修改；校验失败的异常语义与模型直接校验时完全一致。
 */
final class OpenAiCompatibleImageValidator {
    /**
     * 能力校验器，无状态，可被两条链路共用。
     */
    private static final ModelCapabilityGuard GUARD = new ModelCapabilityGuard();

    private OpenAiCompatibleImageValidator() {
    }

    /**
     * 校验请求。
     *
     * @param config     图片配置，提供平台与各项上限
     * @param options    平台扩展点，负责拒绝本平台不支持的字段
     * @param descriptor 模型登记项，提供能力集合
     * @param request    图片请求
     * @throws IllegalArgumentException 请求为 null、平台选项与配置不匹配时
     * @throws com.clara.kernel.ClaraException 模型不具备所需能力、提示词/数量/源图超限，或平台不支持该请求时
     */
    static void validate(OpenAiCompatibleImageConfig config, OpenAiCompatibleImageOptions options,
            ProviderModelDescriptor descriptor, ImageGenerationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        GUARD.require(descriptor, request);
        if (request.getProviderOptions() != null
                && request.getProviderOptions().getProvider() != config.getProvider()) {
            throw new IllegalArgumentException("provider options do not match provider");
        }
        if (request.getPrompt().length() > config.getMaxPromptChars()
                || request.getCount() > config.getMaxOutputs()
                || request.getSourceImageAssetIds().size() > config.getMaxSourceImages()) {
            throw ExceptionUtils.requestTooLarge(config.getProvider());
        }
        options.validate(request);
    }
}
