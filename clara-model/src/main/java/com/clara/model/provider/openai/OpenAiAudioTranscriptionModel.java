package com.clara.model.provider.openai;

import com.clara.media.MediaType;
import com.clara.model.api.ModelProvider;
import com.clara.model.api.media.AudioTranscriptionModel;
import com.clara.model.api.media.AudioTranscriptionRequest;
import com.clara.model.api.media.AudioTranscriptionResult;
import com.clara.model.api.media.TranscriptionSegment;
import com.clara.model.provider.http.ProviderCredentialResolver;
import com.clara.model.provider.http.impl.ProviderHttpResponse;
import com.clara.model.provider.http.ProviderHttpTransport;
import com.clara.model.provider.media.MediaJson;
import com.clara.model.provider.media.MultipartBody;
import com.clara.model.provider.media.ProviderMediaAssetBridge;
import com.clara.model.provider.media.ProviderMediaErrors;
import com.clara.model.provider.media.ProviderMediaHttp;
import com.clara.model.runtime.ModelCapabilityGuard;
import com.clara.model.runtime.ProviderModelDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提供OpenAI音频转写模型能力。
 */
public final class OpenAiAudioTranscriptionModel implements AudioTranscriptionModel {
    private final OpenAiMediaConfig config;
    private final ProviderModelDescriptor descriptor;
    private final ProviderMediaAssetBridge assets;
    private final ProviderMediaHttp http;
    private final MediaJson json = new MediaJson(ModelProvider.OPENAI);
    private final ModelCapabilityGuard guard = new ModelCapabilityGuard();

    public OpenAiAudioTranscriptionModel(OpenAiMediaConfig config, ProviderModelDescriptor descriptor,
            ProviderMediaAssetBridge assets, ProviderHttpTransport transport,
            ProviderCredentialResolver credentials) {
        if (config == null || descriptor == null || assets == null) {
            throw new IllegalArgumentException("dependencies must not be null");
        }
        if (descriptor.getProvider() != ModelProvider.OPENAI
                || !config.getModelId().equals(descriptor.getModelId())) {
            throw new IllegalArgumentException("descriptor does not match config");
        }
        this.config = config; this.descriptor = descriptor; this.assets = assets;
        this.http = new ProviderMediaHttp(ModelProvider.OPENAI, config.getSecretRef(), transport, credentials);
    }
    public AudioTranscriptionResult transcribe(AudioTranscriptionRequest request) {
        if (request == null) { throw new IllegalArgumentException("request must not be null"); }
        guard.require(descriptor, request);
        if (request.getProviderOptions() != null
                && request.getProviderOptions().getProvider() != ModelProvider.OPENAI) {
            throw new IllegalArgumentException("provider options do not match provider");
        }
        if (request.getPrompt() != null && request.getPrompt().length() > config.getMaxTextChars()) {
            throw ProviderMediaErrors.tooLarge(ModelProvider.OPENAI);
        }
        String boundary = "ClaraAudioBoundary00000001";
        MultipartBody body = new MultipartBody(boundary);
        body.text("model", config.getModelId()).text("prompt", request.getPrompt())
                .text("language", request.getLanguage()).text("response_format", "verbose_json");
        if (request.getTemperature() != null)
            body.text("temperature", request.getTemperature().toString());
        body.file("file", "audio.bin", "application/octet-stream",
                assets.inputBytes(request.getAudioAssetId(), MediaType.AUDIO,
                        config.getMaxInputBytes(), request.getContext()));
        ProviderHttpResponse response = http.execute("POST", config.getEndpoint(), body.contentType(),
                body.finish(), request.getContext(), null);
        JsonNode root = json.read(response.getBody());
        String text = MediaJson.requiredText(root, "text", ModelProvider.OPENAI);
        Long duration = seconds(root.get("duration"));
        List<TranscriptionSegment> segments = new ArrayList<TranscriptionSegment>();
        JsonNode values = root.get("segments");
        if (values != null) {
            if (!values.isArray()) { throw ProviderMediaErrors.protocol(ModelProvider.OPENAI); }
            for (JsonNode value : values) {
                Long start = seconds(value.get("start")); Long end = seconds(value.get("end"));
                if (start == null || end == null) { throw ProviderMediaErrors.protocol(ModelProvider.OPENAI); }
                segments.add(new TranscriptionSegment(start.longValue(), end.longValue(),
                        MediaJson.requiredText(value, "text", ModelProvider.OPENAI)));
            }
        }
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("provider", ModelProvider.OPENAI.name()); metadata.put("model", config.getModelId());
        String language = root.hasNonNull("language") ? root.get("language").asText() : request.getLanguage();
        try { return new AudioTranscriptionResult(text, language, duration, segments, metadata); }
        catch (IllegalArgumentException failure) { throw ProviderMediaErrors.protocol(ModelProvider.OPENAI); }
    }
    private static Long seconds(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!value.isNumber() || value.doubleValue() < 0 || Double.isInfinite(value.doubleValue())
                || Double.isNaN(value.doubleValue())) { throw ProviderMediaErrors.protocol(ModelProvider.OPENAI); }
        return Long.valueOf(Math.round(value.doubleValue() * 1000.0d));
    }
}
