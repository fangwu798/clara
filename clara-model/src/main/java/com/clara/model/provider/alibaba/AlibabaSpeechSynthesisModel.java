package com.clara.model.provider.alibaba;

import com.clara.media.MediaType;
import com.clara.model.api.ModelProvider;
import com.clara.model.api.media.SpeechSynthesisModel;
import com.clara.model.api.media.SpeechSynthesisRequest;
import com.clara.model.api.media.SpeechSynthesisResult;
import com.clara.model.provider.http.ProviderCredentialResolver;
import com.clara.model.provider.http.ProviderHttpResponse;
import com.clara.model.provider.http.ProviderHttpTransport;
import com.clara.model.provider.media.MediaJson;
import com.clara.model.provider.media.ProviderMediaAssetBridge;
import com.clara.model.provider.media.ProviderMediaErrors;
import com.clara.model.provider.media.ProviderMediaHttp;
import com.clara.model.runtime.ModelCapabilityGuard;
import com.clara.model.runtime.ProviderModelDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 提供阿里云语音合成模型能力。
 */
public final class AlibabaSpeechSynthesisModel implements SpeechSynthesisModel {
    private final AlibabaAudioConfig config; private final ProviderModelDescriptor descriptor;
    private final ProviderMediaAssetBridge assets; private final ProviderMediaHttp http;
    private final MediaJson json = new MediaJson(ModelProvider.ALIBABA);
    private final ModelCapabilityGuard guard = new ModelCapabilityGuard();
    public AlibabaSpeechSynthesisModel(AlibabaAudioConfig config, ProviderModelDescriptor descriptor,
            ProviderMediaAssetBridge assets, ProviderHttpTransport transport, ProviderCredentialResolver credentials) {
        if (config == null || descriptor == null || assets == null) { throw new IllegalArgumentException("dependencies must not be null"); }
        if (descriptor.getProvider() != ModelProvider.ALIBABA || !config.getModelId().equals(descriptor.getModelId())) {
            throw new IllegalArgumentException("descriptor does not match config");
        }
        this.config = config; this.descriptor = descriptor; this.assets = assets;
        this.http = new ProviderMediaHttp(ModelProvider.ALIBABA, config.getSecretRef(), transport, credentials);
    }
    public SpeechSynthesisResult synthesize(SpeechSynthesisRequest request) {
        if (request == null) { throw new IllegalArgumentException("request must not be null"); }
        guard.require(descriptor, request);
        if (request.getProviderOptions() != null && request.getProviderOptions().getProvider() != ModelProvider.ALIBABA) {
            throw new IllegalArgumentException("provider options do not match provider");
        }
        if (request.getText().length() > config.getMaxTextChars()) { throw ProviderMediaErrors.tooLarge(ModelProvider.ALIBABA); }
        Map<String, Object> input = new LinkedHashMap<String, Object>(); input.put("text", request.getText());
        Map<String, Object> parameters = new LinkedHashMap<String, Object>();
        if (request.getVoice() != null) parameters.put("voice", request.getVoice());
        if (request.getLanguage() != null) parameters.put("language", request.getLanguage());
        parameters.put("format", request.getOutputMimeType());
        if (request.getSampleRateHz() != null) parameters.put("sample_rate", request.getSampleRateHz());
        if (request.getSpeed() != null) parameters.put("speech_rate", request.getSpeed());
        Map<String, Object> body = new LinkedHashMap<String, Object>(); body.put("model", config.getModelId());
        body.put("input", input); body.put("parameters", parameters);
        ProviderHttpResponse response = http.execute("POST", config.getEndpoint(), "application/json",
                json.write(body), request.getContext(), null);
        JsonNode output = json.read(response.getBody()).get("output");
        JsonNode audio = output == null ? null : output.get("audio");
        String url = audio != null && audio.hasNonNull("url") ? audio.get("url").asText()
                : MediaJson.requiredText(output, "audio_url", ModelProvider.ALIBABA);
        String assetId = assets.storeRemote(url, MediaType.AUDIO, request.getOutputMimeType(), request.getContext());
        Long duration = output != null && output.path("duration").canConvertToLong()
                ? Long.valueOf(output.get("duration").longValue()) : null;
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("provider", ModelProvider.ALIBABA.name()); metadata.put("model", config.getModelId());
        return new SpeechSynthesisResult(assetId, duration, metadata);
    }
}
