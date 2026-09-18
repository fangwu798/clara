package com.clara.model.provider.volcengine;

import com.clara.media.MediaType;
import com.clara.media.job.MediaJobProviderOperation;
import com.clara.media.job.MediaProviderJobStatus;
import com.clara.model.api.ModelProvider;
import com.clara.model.api.media.VideoGenerationRequest;
import com.clara.model.provider.http.ProviderCredentialResolver;
import com.clara.model.provider.http.ProviderHttpTransport;
import com.clara.model.provider.http.impl.ProviderInvoker;
import com.clara.model.provider.media.AbstractJsonMediaJobOperation;
import com.clara.model.provider.media.MediaJson;
import com.clara.model.provider.media.ProviderMediaAssetBridge;
import com.clara.model.runtime.ModelCapabilityGuard;
import com.clara.model.runtime.ProviderModelDescriptor;
import com.clara.model.utils.ExceptionUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 负责创建火山引擎视频任务实例。
 */
public final class VolcengineVideoJobFactory {
    private final VolcengineVideoConfig config;
    private final ProviderModelDescriptor descriptor;
    private final ProviderMediaAssetBridge assets;
    private final ProviderInvoker invoker;
    private final MediaJson json = new MediaJson(ModelProvider.VOLCENGINE);
    private final ModelCapabilityGuard guard = new ModelCapabilityGuard();

    public VolcengineVideoJobFactory(VolcengineVideoConfig config, ProviderModelDescriptor descriptor, ProviderMediaAssetBridge assets, ProviderHttpTransport transport, ProviderCredentialResolver credentials) {
        if (config == null || descriptor == null || assets == null) {
            throw new IllegalArgumentException("dependencies must not be null");
        }
        if (descriptor.getProvider() != ModelProvider.VOLCENGINE || !config.getModelId().equals(descriptor.getModelId())) {
            throw new IllegalArgumentException("descriptor does not match config");
        }
        this.config = config;
        this.descriptor = descriptor;
        this.assets = assets;
        this.invoker = new ProviderInvoker(ModelProvider.VOLCENGINE, config.getSecretRef(), transport, credentials);
    }

    public MediaJobProviderOperation create(VideoGenerationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        guard.require(descriptor, request);
        if (request.getProviderOptions() != null && request.getProviderOptions().getProvider() != ModelProvider.VOLCENGINE) {
            throw new IllegalArgumentException("provider options do not match provider");
        }
        int imageCount = request.getReferenceImageAssetIds().size() + (request.getFirstFrameAssetId() == null ? 0 : 1) + (request.getLastFrameAssetId() == null ? 0 : 1);
        if (request.getPrompt().length() > config.getMaxPromptChars() || imageCount > config.getMaxReferenceImages()) {
            throw ExceptionUtils.requestTooLarge(ModelProvider.VOLCENGINE);
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", config.getModelId());
        List<Map<String, Object>> content = new ArrayList<Map<String, Object>>();
        Map<String, Object> text = new LinkedHashMap<String, Object>();
        text.put("type", "text");
        text.put("text", prompt(request));
        content.add(text);
        for (String id : request.getReferenceImageAssetIds()) {
            image(content, id, "reference", request);
        }
        if (request.getFirstFrameAssetId() != null) {
            image(content, request.getFirstFrameAssetId(), "first_frame", request);
        }
        if (request.getLastFrameAssetId() != null) {
            image(content, request.getLastFrameAssetId(), "last_frame", request);
        }
        body.put("content", content);
        return new Operation(config.getTasksEndpoint(), json.write(body), request, assets, invoker);
    }

    private void image(List<Map<String, Object>> content, String id, String role, VideoGenerationRequest request) {
        Map<String, Object> image = new LinkedHashMap<String, Object>();
        image.put("type", "image_url");
        image.put("role", role);
        Map<String, String> value = new LinkedHashMap<String, String>();
        value.put("url", assets.inputReference(id, MediaType.IMAGE, request.getContext()));
        image.put("image_url", value);
        content.add(image);
    }

    private static String prompt(VideoGenerationRequest request) {
        StringBuilder value = new StringBuilder(request.getPrompt());
        if (request.getNegativePrompt() != null) {
            value.append(" --negative_prompt ").append(request.getNegativePrompt());
        }
        if (request.getAspectRatio() != null) {
            value.append(" --ratio ").append(request.getAspectRatio());
        }
        if (request.getDurationMs() != null) {
            value.append(" --duration ").append(request.getDurationMs());
        }
        if (request.getFramesPerSecond() != null) {
            value.append(" --fps ").append(request.getFramesPerSecond());
        }
        if (request.getSeed() != null) {
            value.append(" --seed ").append(request.getSeed());
        }
        return value.toString();
    }

    /**
     * 表示。
     */
    private static final class Operation extends AbstractJsonMediaJobOperation {
        Operation(URI endpoint, byte[] body, VideoGenerationRequest request, ProviderMediaAssetBridge assets, ProviderInvoker invoker) {
            super(ModelProvider.VOLCENGINE, endpoint, body, request.getContext(), assets, invoker);
        }

        protected String idempotencyHeader() {
            return "X-Idempotency-Key";
        }

        protected String taskId(JsonNode root) {
            return MediaJson.requiredText(root, "id", provider());
        }

        protected MediaProviderJobStatus status(JsonNode root) {
            String value = normalized(MediaJson.requiredText(root, "status", provider()));
            if ("QUEUED".equals(value) || "PENDING".equals(value)) {
                return MediaProviderJobStatus.QUEUED;
            }
            if ("RUNNING".equals(value) || "PROCESSING".equals(value)) {
                return MediaProviderJobStatus.RUNNING;
            }
            if ("SUCCEEDED".equals(value) || "SUCCESS".equals(value)) {
                return MediaProviderJobStatus.SUCCEEDED;
            }
            if ("FAILED".equals(value)) {
                return MediaProviderJobStatus.FAILED;
            }
            if ("CANCELLED".equals(value) || "CANCELED".equals(value)) {
                return MediaProviderJobStatus.CANCELLED;
            }
            throw ExceptionUtils.protocolError(provider());
        }

        protected String outputUrl(JsonNode root) {
            JsonNode content = root.get("content");
            if (content != null && content.isObject() && content.hasNonNull("video_url")) {
                return content.get("video_url").asText();
            }
            JsonNode output = root.get("output");
            return MediaJson.requiredText(output, "video_url", provider());
        }

        protected URI cancelEndpoint(String providerJobId) {
            return taskEndpoint(providerJobId);
        }

        protected int progress(JsonNode root, MediaProviderJobStatus status) {
            JsonNode value = root.get("progress");
            if (value != null && value.canConvertToInt()) {
                return Math.max(0, Math.min(100, value.intValue()));
            }
            return super.progress(root, status);
        }

        protected String safeErrorCode(JsonNode root) {
            JsonNode error = root.get("error");
            return error != null && error.hasNonNull("code") ? error.get("code").asText() : super.safeErrorCode(root);
        }
    }
}
