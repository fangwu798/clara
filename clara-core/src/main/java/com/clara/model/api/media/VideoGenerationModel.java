package com.clara.model.api.media;

import com.clara.media.job.MediaJob;

/**
 * 提供视频生成模型能力。
 */
public interface VideoGenerationModel {
    MediaJob submit(VideoGenerationRequest request);
    MediaJob get(String tenantId, String jobId);
    void cancel(String tenantId, String jobId, long expectedVersion);
}
