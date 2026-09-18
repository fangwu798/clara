package com.clara.model.provider.openai;

import com.clara.media.MediaType;
import com.clara.model.api.ModelProvider;
import com.clara.model.api.media.SpeechSynthesisModel;
import com.clara.model.api.media.SpeechSynthesisRequest;
import com.clara.model.api.media.SpeechSynthesisResult;
import com.clara.model.provider.http.ProviderCredentialResolver;
import com.clara.model.provider.http.impl.ProviderHttpResponse;
import com.clara.model.provider.http.ProviderHttpTransport;
import com.clara.model.provider.http.impl.ProviderInvoker;
import com.clara.model.provider.media.MediaJson;
import com.clara.model.provider.media.ProviderMediaAssetBridge;
import com.clara.model.runtime.ModelCapabilityGuard;
import com.clara.model.runtime.ProviderModelDescriptor;
import com.clara.model.utils.ExceptionUtils;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 提供OpenAI语音合成模型能力。
 */
public final class OpenAiSpeechSynthesisModel implements SpeechSynthesisModel {
    private final OpenAiMediaConfig config;
    private final ProviderModelDescriptor descriptor;
    private final ProviderMediaAssetBridge assets;
    private final ProviderInvoker invoker;
    private final MediaJson json = new MediaJson(ModelProvider.OPENAI);
    private final ModelCapabilityGuard guard = new ModelCapabilityGuard();

    public OpenAiSpeechSynthesisModel(OpenAiMediaConfig config, ProviderModelDescriptor descriptor,
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
        this.invoker = new ProviderInvoker(ModelProvider.OPENAI, config.getSecretRef(), transport, credentials);
    }
    public SpeechSynthesisResult synthesize(SpeechSynthesisRequest request) {
        if (request == null) { throw new IllegalArgumentException("request must not be null"); }
        guard.require(descriptor, request);
        if (request.getProviderOptions() != null
                && request.getProviderOptions().getProvider() != ModelProvider.OPENAI) {
            throw new IllegalArgumentException("provider options do not match provider");
        }
        if (request.getText().length() > config.getMaxTextChars()) {
            throw ExceptionUtils.requestTooLarge(ModelProvider.OPENAI);
        }
        if (request.getLanguage() != null || request.getSampleRateHz() != null) {
            throw ExceptionUtils.mediaTransportUnsupported(ModelProvider.OPENAI);
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", config.getModelId()); body.put("input", request.getText());
        body.put("voice", request.getVoice() == null ? "alloy" : request.getVoice());
        body.put("response_format", format(request.getOutputMimeType()));
        if (request.getSpeed() != null) body.put("speed", request.getSpeed());
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Accept", request.getOutputMimeType());
        ProviderHttpResponse response = invoker.execute("POST", config.getEndpoint(), "application/json",
                json.write(body), headers, request.getContext());
        if (response.getBody().length == 0 || response.getBody().length > config.getMaxInputBytes()) {
            throw ExceptionUtils.protocolError(ModelProvider.OPENAI);
        }
        String assetId = assets.storeBytes(response.getBody(), MediaType.AUDIO,
                request.getOutputMimeType(), request.getContext());
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("provider", ModelProvider.OPENAI.name()); metadata.put("model", config.getModelId());
        return new SpeechSynthesisResult(assetId, null, metadata);
    }
    private static String format(String mime) {
        if ("audio/mpeg".equals(mime)) return "mp3";
        if ("audio/wav".equals(mime)) return "wav";
        if ("audio/ogg".equals(mime)) return "opus";
        if ("audio/aac".equals(mime)) return "aac";
        if ("audio/flac".equals(mime)) return "flac";
        throw ExceptionUtils.mediaTransportUnsupported(ModelProvider.OPENAI);
    }
}
