package com.clara.model.provider.alibaba;

import com.clara.kernel.InvocationContext;
import com.clara.media.MediaType;
import com.clara.media.job.MediaJobProviderOperation;
import com.clara.media.job.MediaProviderJobStatus;
import com.clara.model.api.ModelProvider;
import com.clara.model.api.media.ImageGenerationRequest;
import com.clara.model.api.media.VideoGenerationRequest;
import com.clara.model.provider.http.ProviderCredentialResolver;
import com.clara.model.provider.http.ProviderHttpTransport;
import com.clara.model.provider.media.AbstractJsonMediaJobOperation;
import com.clara.model.provider.media.MediaJson;
import com.clara.model.provider.media.ProviderMediaAssetBridge;
import com.clara.model.provider.media.ProviderMediaErrors;
import com.clara.model.provider.media.ProviderMediaHttp;
import com.clara.model.runtime.ModelCapabilityGuard;
import com.clara.model.runtime.ProviderModelDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 负责创建阿里云原生媒体任务实例。
 */
public final class AlibabaNativeMediaJobFactory {
    private final AlibabaNativeTaskConfig config;
    private final ProviderModelDescriptor descriptor;
    private final ProviderMediaAssetBridge assets;
    private final ProviderMediaHttp http;
    private final MediaJson json = new MediaJson(ModelProvider.ALIBABA);
    private final ModelCapabilityGuard guard = new ModelCapabilityGuard();

    public AlibabaNativeMediaJobFactory(AlibabaNativeTaskConfig config,
            ProviderModelDescriptor descriptor, ProviderMediaAssetBridge assets,
            ProviderHttpTransport transport, ProviderCredentialResolver credentials) {
        if (config == null || descriptor == null || assets == null) {
            throw new IllegalArgumentException("dependencies must not be null");
        }
        if (descriptor.getProvider() != ModelProvider.ALIBABA
                || !config.getModelId().equals(descriptor.getModelId())) {
            throw new IllegalArgumentException("descriptor does not match config");
        }
        this.config = config; this.descriptor = descriptor; this.assets = assets;
        this.http = new ProviderMediaHttp(ModelProvider.ALIBABA, config.getSecretRef(), transport, credentials);
    }
    public MediaJobProviderOperation create(ImageGenerationRequest request) {
        if (request == null) { throw new IllegalArgumentException("request must not be null"); }
        guard.require(descriptor, request); providerOptions(request.getProviderOptions());
        if (config.getOutputType() != MediaType.IMAGE) { throw new IllegalArgumentException("config is not for images"); }
        if (request.getPrompt().length() > config.getMaxPromptChars()
                || request.getSourceImageAssetIds().size() > config.getMaxInputImages()) {
            throw ProviderMediaErrors.tooLarge(ModelProvider.ALIBABA);
        }
        Map<String, Object> input = new LinkedHashMap<String, Object>(); input.put("prompt", request.getPrompt());
        addImages(input, request.getSourceImageAssetIds(), request.getContext());
        if (request.getMaskAssetId() != null)
            input.put("mask_url", assets.inputReference(request.getMaskAssetId(), MediaType.IMAGE, request.getContext()));
        Map<String, Object> parameters = new LinkedHashMap<String, Object>();
        parameters.put("n", Integer.valueOf(request.getCount()));
        if (request.getWidth() != null) parameters.put("size", request.getWidth() + "*" + request.getHeight());
        if (request.getAspectRatio() != null) parameters.put("ratio", request.getAspectRatio());
        if (request.getStyle() != null) parameters.put("style", request.getStyle());
        if (request.getSeed() != null) parameters.put("seed", request.getSeed());
        if (request.getNegativePrompt() != null) parameters.put("negative_prompt", request.getNegativePrompt());
        return operation(envelope(input, parameters), request.getContext());
    }
    public MediaJobProviderOperation create(VideoGenerationRequest request) {
        if (request == null) { throw new IllegalArgumentException("request must not be null"); }
        guard.require(descriptor, request); providerOptions(request.getProviderOptions());
        if (config.getOutputType() != MediaType.VIDEO) { throw new IllegalArgumentException("config is not for video"); }
        int imageCount = request.getReferenceImageAssetIds().size()
                + (request.getFirstFrameAssetId() == null ? 0 : 1)
                + (request.getLastFrameAssetId() == null ? 0 : 1);
        if (request.getPrompt().length() > config.getMaxPromptChars()
                || imageCount > config.getMaxInputImages()) {
            throw ProviderMediaErrors.tooLarge(ModelProvider.ALIBABA);
        }
        Map<String, Object> input = new LinkedHashMap<String, Object>(); input.put("prompt", request.getPrompt());
        addImages(input, request.getReferenceImageAssetIds(), request.getContext());
        if (request.getFirstFrameAssetId() != null)
            input.put("first_frame_url", assets.inputReference(request.getFirstFrameAssetId(), MediaType.IMAGE, request.getContext()));
        if (request.getLastFrameAssetId() != null)
            input.put("last_frame_url", assets.inputReference(request.getLastFrameAssetId(), MediaType.IMAGE, request.getContext()));
        Map<String, Object> parameters = new LinkedHashMap<String, Object>();
        if (request.getDurationMs() != null) parameters.put("duration", request.getDurationMs().doubleValue() / 1000.0d);
        if (request.getWidth() != null) parameters.put("size", request.getWidth() + "*" + request.getHeight());
        if (request.getAspectRatio() != null) parameters.put("ratio", request.getAspectRatio());
        if (request.getFramesPerSecond() != null) parameters.put("fps", request.getFramesPerSecond());
        if (request.getSeed() != null) parameters.put("seed", request.getSeed());
        if (request.getNegativePrompt() != null) parameters.put("negative_prompt", request.getNegativePrompt());
        return operation(envelope(input, parameters), request.getContext());
    }
    private Map<String, Object> envelope(Map<String, Object> input, Map<String, Object> parameters) {
        Map<String, Object> body = new LinkedHashMap<String, Object>(); body.put("model", config.getModelId());
        body.put("input", input); if (!parameters.isEmpty()) body.put("parameters", parameters); return body;
    }
    private void addImages(Map<String, Object> input, List<String> ids, InvocationContext context) {
        if (ids.isEmpty()) return;
        List<String> refs = new ArrayList<String>();
        for (String id : ids) refs.add(assets.inputReference(id, MediaType.IMAGE, context));
        input.put(ids.size() == 1 ? "img_url" : "img_urls", ids.size() == 1 ? refs.get(0) : refs);
    }
    private MediaJobProviderOperation operation(Map<String, Object> body, InvocationContext context) {
        return new Operation(config, json.write(body), context, assets, http);
    }
    private static void providerOptions(com.clara.model.api.ProviderOptions options) {
        if (options != null && options.getProvider() != ModelProvider.ALIBABA) {
            throw new IllegalArgumentException("provider options do not match provider");
        }
    }
    /**
     * 表示。
     */
    private static final class Operation extends AbstractJsonMediaJobOperation {
        private final URI queryEndpoint;
        Operation(AlibabaNativeTaskConfig config, byte[] body, InvocationContext context,
                ProviderMediaAssetBridge assets, ProviderMediaHttp http) {
            super(ModelProvider.ALIBABA, config.getSubmitEndpoint(), body, context, assets, http,
                    config.getOutputType(), config.getOutputMimeType());
            this.queryEndpoint = config.getTaskQueryEndpoint();
        }
        protected String idempotencyHeader() { return "X-Clara-Idempotency-Key"; }
        protected Map<String, String> submitHeaders() {
            return Collections.singletonMap("X-DashScope-Async", "enable");
        }
        protected String taskId(JsonNode root) {
            JsonNode output = root.get("output");
            return MediaJson.requiredText(output, "task_id", provider());
        }
        protected URI taskEndpoint(String id) { return append(queryEndpoint, id); }
        protected URI cancelEndpoint(String id) { return taskEndpoint(id); }
        protected MediaProviderJobStatus status(JsonNode root) {
            JsonNode output = root.get("output");
            String value = normalized(MediaJson.requiredText(output, "task_status", provider()));
            if ("PENDING".equals(value) || "QUEUED".equals(value)) return MediaProviderJobStatus.QUEUED;
            if ("RUNNING".equals(value)) return MediaProviderJobStatus.RUNNING;
            if ("SUCCEEDED".equals(value) || "SUCCESS".equals(value)) return MediaProviderJobStatus.SUCCEEDED;
            if ("FAILED".equals(value) || "UNKNOWN".equals(value)) return MediaProviderJobStatus.FAILED;
            if ("CANCELED".equals(value) || "CANCELLED".equals(value)) return MediaProviderJobStatus.CANCELLED;
            throw ProviderMediaErrors.protocol(provider());
        }
        protected String outputUrl(JsonNode root) {
            JsonNode output = root.get("output");
            for (String field : new String[] {"video_url", "image_url", "audio_url", "url"})
                if (output != null && output.hasNonNull(field)) return output.get(field).asText();
            JsonNode results = output == null ? null : output.get("results");
            if (results != null && results.isArray() && results.size() > 0)
                return MediaJson.requiredText(results.get(0), "url", provider());
            throw ProviderMediaErrors.protocol(provider());
        }
        protected int progress(JsonNode root, MediaProviderJobStatus status) {
            JsonNode output = root.get("output"); JsonNode value = output == null ? null : output.get("progress");
            return value != null && value.canConvertToInt() ? Math.max(0, Math.min(100, value.intValue()))
                    : super.progress(root, status);
        }
        protected String safeErrorCode(JsonNode root) {
            return root.hasNonNull("code") ? root.get("code").asText() : super.safeErrorCode(root);
        }
    }
}
