package com.clara.model.provider.alibaba;

import com.clara.media.MediaType;
import com.clara.model.api.ModelProvider;
import com.clara.model.api.media.AudioTranscriptionModel;
import com.clara.model.api.media.AudioTranscriptionRequest;
import com.clara.model.api.media.AudioTranscriptionResult;
import com.clara.model.api.media.TranscriptionSegment;
import com.clara.model.provider.http.ProviderCredentialResolver;
import com.clara.model.provider.http.impl.ProviderHttpResponse;
import com.clara.model.provider.http.ProviderHttpTransport;
import com.clara.model.provider.http.impl.ProviderInvoker;
import com.clara.model.provider.media.MediaJson;
import com.clara.model.provider.media.ProviderMediaAssetBridge;
import com.clara.model.runtime.ModelCapabilityGuard;
import com.clara.model.runtime.ProviderModelDescriptor;
import com.clara.model.utils.ExceptionUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提供阿里云音频转写模型能力。
 */
public final class AlibabaAudioTranscriptionModel implements AudioTranscriptionModel {
    private final AlibabaAudioConfig config;
    private final ProviderModelDescriptor descriptor;
    private final ProviderMediaAssetBridge assets;
    private final ProviderInvoker invoker;
    private final MediaJson json = new MediaJson(ModelProvider.ALIBABA);
    private final ModelCapabilityGuard guard = new ModelCapabilityGuard();

    public AlibabaAudioTranscriptionModel(AlibabaAudioConfig config, ProviderModelDescriptor descriptor,
            ProviderMediaAssetBridge assets, ProviderHttpTransport transport,
            ProviderCredentialResolver credentials) {
        require(config, descriptor, assets);
        this.config = config; this.descriptor = descriptor; this.assets = assets;
        this.invoker = new ProviderInvoker(ModelProvider.ALIBABA, config.getSecretRef(), transport, credentials);
    }
    public AudioTranscriptionResult transcribe(AudioTranscriptionRequest request) {
        if (request == null) { throw new IllegalArgumentException("request must not be null"); }
        guard.require(descriptor, request); options(request.getProviderOptions());
        if (request.getPrompt() != null && request.getPrompt().length() > config.getMaxTextChars()) {
            throw ExceptionUtils.requestTooLarge(ModelProvider.ALIBABA);
        }
        Map<String, Object> input = new LinkedHashMap<String, Object>();
        input.put("audio", assets.inputReference(request.getAudioAssetId(), MediaType.AUDIO, request.getContext()));
        if (request.getPrompt() != null) input.put("prompt", request.getPrompt());
        Map<String, Object> parameters = new LinkedHashMap<String, Object>();
        if (request.getLanguage() != null) parameters.put("language", request.getLanguage());
        if (request.getTemperature() != null) parameters.put("temperature", request.getTemperature());
        if (request.getResponseFormat() != null) parameters.put("result_format", request.getResponseFormat());
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", config.getModelId()); body.put("input", input); body.put("parameters", parameters);
        ProviderHttpResponse response = invoker.execute("POST", config.getEndpoint(), "application/json",
                json.write(body), null, request.getContext());
        JsonNode root = json.read(response.getBody()); JsonNode output = root.get("output");
        String text = text(output);
        String language = output != null && output.hasNonNull("language") ? output.get("language").asText() : request.getLanguage();
        Long duration = millis(output == null ? null : output.get("duration"));
        List<TranscriptionSegment> segments = segments(output == null ? null : output.get("segments"));
        Map<String, String> metadata = metadata();
        try { return new AudioTranscriptionResult(text, language, duration, segments, metadata); }
        catch (IllegalArgumentException failure) { throw ExceptionUtils.protocolError(ModelProvider.ALIBABA); }
    }
    private String text(JsonNode output) {
        if (output != null && output.hasNonNull("text")) return output.get("text").asText();
        JsonNode choices = output == null ? null : output.get("choices");
        JsonNode content = choices != null && choices.isArray() && choices.size() > 0
                ? choices.get(0).path("message").get("content") : null;
        if (content != null && content.isArray())
            for (JsonNode item : content) if (item.hasNonNull("text")) return item.get("text").asText();
        throw ExceptionUtils.protocolError(ModelProvider.ALIBABA);
    }
    private List<TranscriptionSegment> segments(JsonNode values) {
        if (values == null || values.isNull()) return Collections.emptyList();
        if (!values.isArray()) { throw ExceptionUtils.protocolError(ModelProvider.ALIBABA); }
        List<TranscriptionSegment> result = new ArrayList<TranscriptionSegment>();
        for (JsonNode value : values) result.add(new TranscriptionSegment(requiredLong(value, "start_time"),
                requiredLong(value, "end_time"), MediaJson.requiredText(value, "text", ModelProvider.ALIBABA)));
        return result;
    }
    private static long requiredLong(JsonNode value, String field) {
        JsonNode number = value.get(field);
        if (number == null || !number.canConvertToLong() || number.longValue() < 0) {
            throw ExceptionUtils.protocolError(ModelProvider.ALIBABA);
        }
        return number.longValue();
    }
    private static Long millis(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!value.isNumber() || value.doubleValue() < 0) { throw ExceptionUtils.protocolError(ModelProvider.ALIBABA); }
        return Long.valueOf(Math.round(value.doubleValue() * 1000.0d));
    }
    private Map<String, String> metadata() {
        Map<String, String> value = new LinkedHashMap<String, String>();
        value.put("provider", ModelProvider.ALIBABA.name()); value.put("model", config.getModelId()); return value;
    }
    private static void options(com.clara.model.api.ProviderOptions value) {
        if (value != null && value.getProvider() != ModelProvider.ALIBABA) {
            throw new IllegalArgumentException("provider options do not match provider");
        }
    }
    private static void require(AlibabaAudioConfig config, ProviderModelDescriptor descriptor,
            ProviderMediaAssetBridge assets) {
        if (config == null || descriptor == null || assets == null) { throw new IllegalArgumentException("dependencies must not be null"); }
        if (descriptor.getProvider() != ModelProvider.ALIBABA || !config.getModelId().equals(descriptor.getModelId())) {
            throw new IllegalArgumentException("descriptor does not match config");
        }
    }
}
