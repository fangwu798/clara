package com.clara.model.api.media;

import com.clara.media.job.MediaJob;

public interface VideoGenerationModel {
    MediaJob submit(VideoGenerationRequest request);
    MediaJob get(String tenantId, String jobId);
    void cancel(String tenantId, String jobId, long expectedVersion);
}
