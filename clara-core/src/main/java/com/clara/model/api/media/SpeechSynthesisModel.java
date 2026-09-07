package com.clara.model.api.media;

/**
 * 提供语音合成模型能力。
 */
public interface SpeechSynthesisModel {
    SpeechSynthesisResult synthesize(SpeechSynthesisRequest request);
}
