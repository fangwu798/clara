package com.clara.model.provider.volcengine;

import com.clara.media.MediaType;
import com.clara.model.api.ModelProvider;
import com.clara.model.api.media.ImageGenerationModel;
import com.clara.model.api.media.ImageGenerationRequest;
import com.clara.model.api.media.ImageGenerationResult;
import com.clara.model.provider.http.ProviderCredentialResolver;
import com.clara.model.provider.http.impl.ProviderHttpResponse;
import com.clara.model.provider.http.ProviderHttpTransport;
import com.clara.model.provider.media.MediaJson;
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
 * 提供火山引擎图像模型能力。
 */
public final class VolcengineImageModel implements ImageGenerationModel {
    private final VolcengineImageConfig config;
    private final ProviderModelDescriptor descriptor;
    private final ProviderMediaAssetBridge assets;
    private final ProviderMediaHttp http;
    private final MediaJson json = new MediaJson(ModelProvider.VOLCENGINE);
    private final ModelCapabilityGuard guard = new ModelCapabilityGuard();

    public VolcengineImageModel(VolcengineImageConfig config, ProviderModelDescriptor descriptor,
            ProviderMediaAssetBridge assets, ProviderHttpTransport transport,
            ProviderCredentialResolver credentials) {
        if (config == null || descriptor == null || assets == null) {
            throw new IllegalArgumentException("dependencies must not be null");
        }
        if (descriptor.getProvider() != ModelProvider.VOLCENGINE
                || !config.getModelId().equals(descriptor.getModelId())) {
            throw new IllegalArgumentException("descriptor does not match config");
        }
        this.config = config;
        this.descriptor = descriptor;
        this.assets = assets;
        this.http = new ProviderMediaHttp(ModelProvider.VOLCENGINE, config.getSecretRef(),
                transport, credentials);
    }

    public ImageGenerationResult generate(ImageGenerationRequest request) {
        if (request == null) { throw new IllegalArgumentException("request must not be null"); }
        guard.require(descriptor, request);
        if (request.getProviderOptions() != null
                && request.getProviderOptions().getProvider() != ModelProvider.VOLCENGINE) {
            throw new IllegalArgumentException("provider options do not match provider");
        }
        if (request.getPrompt().length() > config.getMaxPromptChars()
                || request.getCount() > config.getMaxImages()
                || request.getSourceImageAssetIds().size() > config.getMaxSourceImages()) {
            throw ProviderMediaErrors.tooLarge(ModelProvider.VOLCENGINE);
        }
        if (request.getMaskAssetId() != null) {
            throw ProviderMediaErrors.transportUnsupported(ModelProvider.VOLCENGINE);
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", config.getModelId());
        body.put("prompt", request.getPrompt());
        body.put("response_format", "url");
        body.put("watermark", Boolean.valueOf(config.isWatermark()));
        body.put("n", Integer.valueOf(request.getCount()));
        if (request.getWidth() != null)
            body.put("size", request.getWidth() + "x" + request.getHeight());
        else if (request.getAspectRatio() != null) body.put("size", request.getAspectRatio());
        if (request.getSeed() != null) body.put("seed", request.getSeed());
        if (request.getNegativePrompt() != null) body.put("negative_prompt", request.getNegativePrompt());
        List<String> inputs = new ArrayList<String>();
        for (String assetId : request.getSourceImageAssetIds())
            inputs.add(assets.inputReference(assetId, MediaType.IMAGE, request.getContext()));
        if (inputs.size() == 1) body.put("image", inputs.get(0));
        else if (!inputs.isEmpty()) body.put("image", inputs);
        ProviderHttpResponse response = http.execute("POST", config.getEndpoint(),
                "application/json", json.write(body), request.getContext(), null);
        JsonNode root = json.read(response.getBody());
        JsonNode data = root.get("data");
        if (data == null || !data.isArray() || data.size() != request.getCount()) {
            throw ProviderMediaErrors.protocol(ModelProvider.VOLCENGINE);
        }
        List<String> output = new ArrayList<String>();
        for (JsonNode item : data) {
            String url = MediaJson.requiredText(item, "url", ModelProvider.VOLCENGINE);
            output.add(assets.storeRemote(url, MediaType.IMAGE, "image/*", request.getContext()));
        }
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("provider", ModelProvider.VOLCENGINE.name());
        metadata.put("model", config.getModelId());
        String requestId = root.hasNonNull("request_id") ? root.get("request_id").asText() : null;
        return new ImageGenerationResult(output, requestId, metadata);
    }
}
