package com.clara.core.media;

/**
 * 视频生成能力契约：由模型层按平台实现，工作流层只依赖本接口。
 *
 * <p>实现方负责提交异步任务、轮询任务状态，并在成功后返回可下载的视频地址；调用方负责展示进度与
 * 本地下载。该接口不暴露平台配置类型，避免上层依赖具体模型模块。
 */
public interface IVideoGenerator {

    /**
     * 生成一个视频并等待任务完成。
     *
     * @param request 视频生成请求，不可为 null
     * @return 生成结果，非 null
     * @throws IllegalArgumentException 请求非法时
     * @throws IllegalStateException    平台返回失败、任务超时或结果缺少视频地址时
     */
    VideoGenerationResult generate(VideoGenerationRequest request);
}
