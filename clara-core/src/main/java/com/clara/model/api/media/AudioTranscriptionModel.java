package com.clara.model.api.media;

public interface AudioTranscriptionModel {
    AudioTranscriptionResult transcribe(AudioTranscriptionRequest request);
}
