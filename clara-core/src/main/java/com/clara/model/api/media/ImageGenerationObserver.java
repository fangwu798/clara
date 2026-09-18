package com.clara.model.api.media;

/**
 * 观察图片生成流事件的回调。
 *
 * <p>用途：由 {@link StreamingImageGenerationModel} 在读取平台事件流的线程上按序回调，调用方据此即时
 * 展示已生成或失败的图片。实现必须足够快，或自行把耗时处理转移到别的线程，否则会阻塞整条流的读取。
 *
 * <p>注意：同一观察者的事件按序回调；{@link ImageGenerationEvent.Type#RESPONSE_COMPLETE} 与
 * {@link ImageGenerationEvent.Type#ERROR} 只回调一次，且之后不会再有事件。
 */
public interface ImageGenerationObserver {
    /**
     * 处理一个图片流事件。
     *
     * @param event 图片流事件
     */
    void onEvent(ImageGenerationEvent event);
}
