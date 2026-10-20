package com.clara.model.video;

import com.clara.core.media.IVideoGenerator;
import com.clara.enums.CapabilityKindEnum;
import com.clara.enums.ModelProviderEnum;
import com.clara.kernel.config.ModelConfig;
import com.clara.kernel.config.ProviderConfiguration;

/**
 * 视频生成器工厂：按平台配置选择具体实现。
 *
 * <p>目前方舟（VOLCENGINE）的异步任务协议已实现；其他平台未接入时明确失败，避免误用同步图片接口。
 */
public final class VideoGeneratorFactory {

    private VideoGeneratorFactory() {
    }

    /**
     * 按平台配置创建视频生成器。
     *
     * @param configuration 平台配置，不可为 null，且必须声明 VIDEO 能力
     * @return 视频生成器，非 null
     * @throws IllegalArgumentException 配置为 null、未声明 VIDEO 能力或平台暂不支持时
     */
    public static IVideoGenerator create(ProviderConfiguration configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("configuration must not be null");
        }
        ModelConfig video = configuration.toConfig(CapabilityKindEnum.VIDEO);
        if (video.getProvider() == ModelProviderEnum.VOLCENGINE) {
            return new ArkVideoTaskClient(configuration);
        }
        throw new IllegalArgumentException("暂不支持该平台的视频生成：" + video.getProvider());
    }
}
