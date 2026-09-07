package com.clara.model.provider.media;

import com.clara.kernel.ClaraException;
import com.clara.kernel.InvocationContext;
import com.clara.media.MediaType;
import com.clara.media.MediaUpload;
import com.clara.media.job.MediaJobProviderException;
import com.clara.media.job.MediaJobProviderOperation;
import com.clara.media.job.MediaProviderJobSnapshot;
import com.clara.media.job.MediaProviderJobStatus;
import com.clara.model.api.ModelProvider;
import com.clara.model.provider.http.ProviderHttpResponse;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 提供JSON媒体任务操作的抽象基础实现。
 */
public abstract class AbstractJsonMediaJobOperation implements MediaJobProviderOperation {
    private final ModelProvider provider;
    private final URI submitEndpoint;
    private final byte[] submitBody;
    private final InvocationContext context;
    private final ProviderMediaAssetBridge assets;
    private final ProviderMediaHttp http;
    private final MediaJson json;
    private final MediaType outputType;
    private final String outputMimeType;

    protected AbstractJsonMediaJobOperation(
            ModelProvider provider,
            URI submitEndpoint,
            byte[] submitBody,
            InvocationContext context,
            ProviderMediaAssetBridge assets,
            ProviderMediaHttp http) {
        this(provider, submitEndpoint, submitBody, context, assets, http, MediaType.VIDEO, "video/mp4");
    }

    protected AbstractJsonMediaJobOperation(
            ModelProvider provider,
            URI submitEndpoint,
            byte[] submitBody,
            InvocationContext context,
            ProviderMediaAssetBridge assets,
            ProviderMediaHttp http,
            MediaType outputType,
            String outputMimeType) {
        if (provider == null || submitEndpoint == null || submitBody == null || context == null
                || assets == null || http == null || outputType == null || outputMimeType == null) {
            throw new IllegalArgumentException("dependencies must not be null");
        }
        this.provider = provider;
        this.submitEndpoint = submitEndpoint;
        this.submitBody = submitBody.clone();
        this.context = context;
        this.assets = assets;
        this.http = http;
        this.json = new MediaJson(provider);
        this.outputType = outputType;
        this.outputMimeType = outputMimeType;
    }

    public boolean supportsIdempotentSubmission() {
        return true;
    }

    public String submit(String idempotencyKey) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put(idempotencyHeader(), idempotencyKey);
        headers.putAll(submitHeaders());
        try {
            JsonNode root = json.read(http.execute("POST", submitEndpoint, "application/json",
                    submitBody, context, headers).getBody());
            return taskId(root);
        } catch (ClaraException failure) {
            throw mapped(failure, true);
        }
    }

    public MediaProviderJobSnapshot poll(String providerJobId) {
        try {
            JsonNode root = json.read(http.execute("GET", taskEndpoint(providerJobId), null,
                    new byte[0], context, null).getBody());
            MediaProviderJobStatus status = status(root);
            int progress = progress(root, status);
            if (status == MediaProviderJobStatus.SUCCEEDED) {
                String url = outputUrl(root);
                MediaUpload output = assets.remoteUpload(url, outputType, outputMimeType(), context);
                return new MediaProviderJobSnapshot(status, 100, null, output);
            }
            if (status == MediaProviderJobStatus.FAILED)
                return new MediaProviderJobSnapshot(status, progress, safeErrorCode(root), null);
            return new MediaProviderJobSnapshot(status, progress, null, null);
        } catch (ClaraException failure) {
            throw mapped(failure, false);
        }
    }

    public void cancel(String providerJobId) {
        URI endpoint = cancelEndpoint(providerJobId);
        if (endpoint == null) return;
        try {
            http.execute("DELETE", endpoint, null, new byte[0], context, null);
        } catch (ClaraException failure) {
            throw mapped(failure, false);
        }
    }

    protected abstract String idempotencyHeader();

    protected abstract String taskId(JsonNode root);

    protected abstract MediaProviderJobStatus status(JsonNode root);

    protected abstract String outputUrl(JsonNode root);

    protected abstract URI cancelEndpoint(String providerJobId);

    protected Map<String, String> submitHeaders() {
        return Collections.emptyMap();
    }

    protected int progress(JsonNode root, MediaProviderJobStatus status) {
        return status == MediaProviderJobStatus.SUCCEEDED ? 100 : 0;
    }

    protected String safeErrorCode(JsonNode root) {
        return "PROVIDER_JOB_FAILED";
    }

    protected String outputMimeType() {
        return outputMimeType;
    }

    protected URI taskEndpoint(String id) {
        return append(submitEndpoint, id);
    }

    protected final ModelProvider provider() {
        return provider;
    }

    protected static URI append(URI base, String id) {
        if (id == null || !id.matches("[A-Za-z0-9._:-]{1,256}")) {
            throw new IllegalArgumentException("invalid provider job id");
        }
        try {
            String path = base.getPath();
            if (!path.endsWith("/")) path += "/";
            return new URI(base.getScheme(), base.getUserInfo(), base.getHost(), base.getPort(),
                    path + id, null, null);
        } catch (URISyntaxException failure) {
            throw new IllegalArgumentException("invalid task endpoint", failure);
        }
    }

    protected static String normalized(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static MediaJobProviderException mapped(ClaraException failure, boolean submission) {
        boolean retryable = failure.getError().isRetryable();
        return new MediaJobProviderException("provider media task failed", retryable,
                submission && retryable, failure);
    }
}
