package com.clara.model.provider.openai;

import com.clara.media.MediaType;
import com.clara.model.api.ModelProvider;
import com.clara.model.api.media.ImageGenerationModel;
import com.clara.model.api.media.ImageGenerationRequest;
import com.clara.model.api.media.ImageGenerationResult;
import com.clara.model.provider.http.ProviderCredentialResolver;
import com.clara.model.provider.http.ProviderHttpResponse;
import com.clara.model.provider.http.ProviderHttpTransport;
import com.clara.model.provider.media.MediaJson;
import com.clara.model.provider.media.MultipartBody;
import com.clara.model.provider.media.ProviderMediaAssetBridge;
import com.clara.model.provider.media.ProviderMediaErrors;
import com.clara.model.provider.media.ProviderMediaHttp;
import com.clara.model.runtime.ModelCapabilityGuard;
import com.clara.model.runtime.ProviderModelDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提供OpenAI图像模型能力。
 */
public final class OpenAiImageModel implements ImageGenerationModel {
    private final OpenAiMediaConfig config;
    private final ProviderModelDescriptor descriptor;
    private final ProviderMediaAssetBridge assets;
    private final ProviderMediaHttp http;
    private final MediaJson json = new MediaJson(ModelProvider.OPENAI);
    private final ModelCapabilityGuard guard = new ModelCapabilityGuard();

    public OpenAiImageModel(OpenAiMediaConfig config, ProviderModelDescriptor descriptor,
            ProviderMediaAssetBridge assets, ProviderHttpTransport transport,
            ProviderCredentialResolver credentials) {
        require(config, descriptor, assets);
        this.config = config;
        this.descriptor = descriptor;
        this.assets = assets;
        this.http = new ProviderMediaHttp(ModelProvider.OPENAI, config.getSecretRef(), transport, credentials);
    }
    public ImageGenerationResult generate(ImageGenerationRequest request) {
        if (request == null) { throw new IllegalArgumentException("request must not be null"); }
        guard.require(descriptor, request);
        validate(request);
        ProviderHttpResponse response = request.isEdit() ? edit(request) : create(request);
        JsonNode root = json.read(response.getBody());
        JsonNode data = root.get("data");
        if (data == null || !data.isArray() || data.size() != request.getCount()) {
            throw ProviderMediaErrors.protocol(ModelProvider.OPENAI);
        }
        List<String> ids = new ArrayList<String>();
        for (JsonNode item : data) {
            if (item.hasNonNull("b64_json")) {
                try {
                    ids.add(assets.storeBytes(Base64.getDecoder().decode(item.get("b64_json").asText()),
                            MediaType.IMAGE, "image/png", request.getContext()));
                } catch (IllegalArgumentException failure) {
                    throw ProviderMediaErrors.protocol(ModelProvider.OPENAI);
                }
            } else {
                ids.add(assets.storeRemote(MediaJson.requiredText(item, "url", ModelProvider.OPENAI),
                        MediaType.IMAGE, "image/*", request.getContext()));
            }
        }
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("provider", ModelProvider.OPENAI.name());
        metadata.put("model", config.getModelId());
        String id = root.hasNonNull("id") ? root.get("id").asText() : null;
        return new ImageGenerationResult(ids, id, metadata);
    }
    private ProviderHttpResponse create(ImageGenerationRequest request) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        common(body, request);
        return http.execute("POST", config.getEndpoint(), "application/json", json.write(body),
                request.getContext(), null);
    }
    private ProviderHttpResponse edit(ImageGenerationRequest request) {
        MultipartBody body = new MultipartBody(boundary(request.getContext().getTraceId()));
        body.text("model", config.getModelId()).text("prompt", request.getPrompt())
                .text("n", String.valueOf(request.getCount()));
        if (request.getWidth() != null) body.text("size", request.getWidth() + "x" + request.getHeight());
        if (request.getQuality() != null) body.text("quality", request.getQuality());
        int index = 0;
        for (String assetId : request.getSourceImageAssetIds()) {
            byte[] bytes = assets.inputBytes(assetId, MediaType.IMAGE, config.getMaxInputBytes(), request.getContext());
            body.file(request.getSourceImageAssetIds().size() == 1 ? "image" : "image[]",
                    "image-" + (++index) + ".bin", "application/octet-stream", bytes);
        }
        if (request.getMaskAssetId() != null)
            body.file("mask", "mask.bin", "application/octet-stream",
                    assets.inputBytes(request.getMaskAssetId(), MediaType.IMAGE,
                            config.getMaxInputBytes(), request.getContext()));
        return http.execute("POST", config.getEndpoint(), body.contentType(), body.finish(),
                request.getContext(), null);
    }
    private void common(Map<String, Object> body, ImageGenerationRequest request) {
        body.put("model", config.getModelId()); body.put("prompt", request.getPrompt());
        body.put("n", Integer.valueOf(request.getCount())); body.put("response_format", "b64_json");
        if (request.getWidth() != null) body.put("size", request.getWidth() + "x" + request.getHeight());
        if (request.getQuality() != null) body.put("quality", request.getQuality());
        if (request.getStyle() != null) body.put("style", request.getStyle());
    }
    private void validate(ImageGenerationRequest request) {
        if (request == null) { throw new IllegalArgumentException("request must not be null"); }
        if (request.getProviderOptions() != null
                && request.getProviderOptions().getProvider() != ModelProvider.OPENAI) {
            throw new IllegalArgumentException("provider options do not match provider");
        }
        if (request.getPrompt().length() > config.getMaxTextChars()
                || request.getCount() > config.getMaxOutputs()) {
            throw ProviderMediaErrors.tooLarge(ModelProvider.OPENAI);
        }
        if (request.getAspectRatio() != null || request.getSeed() != null
                || request.getNegativePrompt() != null) {
            throw ProviderMediaErrors.transportUnsupported(ModelProvider.OPENAI);
        }
    }
    private static String boundary(String traceId) {
        String clean = traceId.replaceAll("[^A-Za-z0-9_-]", "");
        String padded = (clean + "0000000000000000").substring(0, 16);
        return "ClaraBoundary" + padded;
    }
    private static void require(OpenAiMediaConfig config, ProviderModelDescriptor descriptor,
            ProviderMediaAssetBridge assets) {
        if (config == null || descriptor == null || assets == null) {
            throw new IllegalArgumentException("dependencies must not be null");
        }
        if (descriptor.getProvider() != ModelProvider.OPENAI
                || !config.getModelId().equals(descriptor.getModelId())) {
            throw new IllegalArgumentException("descriptor does not match config");
        }
    }
}
