package com.clara.model.api.media;

import com.clara.model.api.StreamHandle;

/**
 * 提供流式图像生成模型能力。
 *
 * <p>用途：把「提交请求 → 逐张产出 → 收尾」的过程暴露成事件流。与非流式的
 * {@link ImageGenerationModel} 相比，调用方不必等整组图片全部生成完，每张图一落盘就能拿到资产 ID 并展示，
 * 单张失败也不会拖垮整条流。
 *
 * <p>注意：事件在读取平台事件流的线程上按序回调；{@link ImageGenerationEvent.Type#RESPONSE_COMPLETE} 与
 * {@link ImageGenerationEvent.Type#ERROR} 是终态事件，一次调用只会出现其中一个。
 */
public interface StreamingImageGenerationModel {
    /**
     * 提交一次流式生图调用。
     *
     * @param request  图片请求，不可为 null
     * @param observer 事件观察者，不可为 null
     * @return 可用于查询完成状态或取消的句柄
     */
    StreamHandle generate(ImageGenerationRequest request, ImageGenerationObserver observer);
}
