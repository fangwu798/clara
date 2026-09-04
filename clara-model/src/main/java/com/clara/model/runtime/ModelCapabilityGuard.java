package com.clara.model.runtime;

import com.clara.model.api.ModelCapability;
import com.clara.model.api.media.AudioTranscriptionRequest;
import com.clara.model.api.media.ImageGenerationRequest;
import com.clara.model.api.media.SpeechSynthesisRequest;
import com.clara.model.api.media.VideoGenerationRequest;
import com.clara.kernel.ErrorCodes;

public final class ModelCapabilityGuard {
    public void require(ProviderModelDescriptor descriptor, ModelCapability capability) {
        if (descriptor == null) throw new IllegalArgumentException("descriptor must not be null");
        if (capability == null) throw new IllegalArgumentException("capability must not be null");
        if (!descriptor.supports(capability)) {
            throw RuntimeErrors.capability(ErrorCodes.CAPABILITY_NOT_SUPPORTED);
        }
    }

    public void require(ProviderModelDescriptor descriptor, ImageGenerationRequest request) {
        if (request == null) throw new IllegalArgumentException("request must not be null");
        require(descriptor, ModelCapability.IMAGE_GENERATION);
        if (request.getCount() > 1) require(descriptor, ModelCapability.IMAGE_BATCH_GENERATION);
        if (request.isEdit()) require(descriptor, ModelCapability.IMAGE_EDIT);
        if (request.isMaskEdit()) require(descriptor, ModelCapability.IMAGE_MASK_EDIT);
    }

    public void require(ProviderModelDescriptor descriptor, AudioTranscriptionRequest request) {
        if (request == null) throw new IllegalArgumentException("request must not be null");
        require(descriptor, ModelCapability.AUDIO_TRANSCRIPTION);
    }

    public void require(ProviderModelDescriptor descriptor, SpeechSynthesisRequest request) {
        if (request == null) throw new IllegalArgumentException("request must not be null");
        require(descriptor, ModelCapability.SPEECH_SYNTHESIS);
    }

    public void require(ProviderModelDescriptor descriptor, VideoGenerationRequest request) {
        if (request == null) throw new IllegalArgumentException("request must not be null");
        require(descriptor, ModelCapability.VIDEO_GENERATION);
        if (!request.getReferenceImageAssetIds().isEmpty() || request.getFirstFrameAssetId() != null
                || request.getLastFrameAssetId() != null)
            require(descriptor, ModelCapability.VIDEO_IMAGE_REFERENCE);
    }
}
