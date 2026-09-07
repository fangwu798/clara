package com.clara.model.api.media;

/**
 * 提供音频转写模型能力。
 */
public interface AudioTranscriptionModel {
    AudioTranscriptionResult transcribe(AudioTranscriptionRequest request);
}
