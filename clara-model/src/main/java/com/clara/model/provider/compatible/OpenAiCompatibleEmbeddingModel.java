package com.clara.model.provider.compatible;

import com.clara.kernel.ClaraError;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCategory;
import com.clara.kernel.ErrorCodes;
import com.clara.model.api.EmbeddingModel;
import com.clara.model.api.EmbeddingRequest;
import com.clara.model.api.EmbeddingResponse;
import com.clara.model.provider.http.ProviderCredentialResolver;
import com.clara.model.provider.http.ProviderHttpRequest;
import com.clara.model.provider.http.ProviderHttpResponse;
import com.clara.model.provider.http.ProviderHttpTransport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提供OpenAI兼容嵌入模型能力。
 */
public final class OpenAiCompatibleEmbeddingModel implements EmbeddingModel {
    private final OpenAiCompatibleEmbeddingConfig config;
    private final ProviderHttpTransport transport;
    private final ProviderCredentialResolver credentials;
    private final ObjectMapper mapper;

    public OpenAiCompatibleEmbeddingModel(OpenAiCompatibleEmbeddingConfig config,
            ProviderHttpTransport transport, ProviderCredentialResolver credentials) {
        if (config == null || transport == null || credentials == null) {
            throw new IllegalArgumentException("dependencies must not be null");
        }
        this.config = config;
        this.transport = transport;
        this.credentials = credentials;
        this.mapper = new ObjectMapper();
    }

    public EmbeddingResponse embed(EmbeddingRequest request) {
        if (request == null) { throw new IllegalArgumentException("request must not be null"); }
        request.getContext().getCancellationToken()
                .throwIfCancellationRequested(request.getContext().getTraceId());
        byte[] payload = encode(request);
        char[] resolved = credentials.resolve(request.getContext().getTenantId(), config.getSecretRef());
        if (resolved == null || resolved.length == 0) credentialUnavailable();
        char[] secret = resolved.clone();
        try {
            Map<String, String> headers = new LinkedHashMap<String, String>();
            headers.put("Authorization", "Bearer " + new String(secret));
            headers.put("Content-Type", "application/json");
            headers.put("Accept", "application/json");
            ProviderHttpResponse response = transport.execute(new ProviderHttpRequest("POST",
                    config.getEndpoint(), headers, payload), request.getContext());
            if (response == null) protocol();
            if (response.getStatusCode() < 200 || response.getStatusCode() >= 300)
                providerFailure(response.getStatusCode());
            return decode(response.getBody(), request.getTexts().size());
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    private byte[] encode(EmbeddingRequest request) {
        List<String> texts = request.getTexts();
        if (texts.size() > config.getMaxInputs()) resourceLimit();
        long total = 0L;
        for (String text : texts) {
            if (text.length() > config.getMaxTextChars()) resourceLimit();
            total += text.length();
            if (total > config.getMaxTotalChars()) resourceLimit();
        }
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("model", config.getModelId());
        value.put("input", texts);
        value.put("encoding_format", "float");
        try { return mapper.writeValueAsBytes(value); }
        catch (JsonProcessingException exception) { throw protocolException(); }
    }

    private EmbeddingResponse decode(byte[] body, int expectedCount) {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode data = root == null ? null : root.get("data");
            if (root == null || !root.isObject() || data == null || !data.isArray()
                    || data.size() != expectedCount) { throw protocolException(); }
            float[][] ordered = new float[expectedCount][];
            for (JsonNode item : data) {
                JsonNode index = item.get("index");
                JsonNode embedding = item.get("embedding");
                if (index == null || !index.canConvertToInt() || embedding == null
                        || !embedding.isArray()) { throw protocolException(); }
                int position = index.intValue();
                if (position < 0 || position >= expectedCount || ordered[position] != null
                        || embedding.size() != config.getDimension()) { throw protocolException(); }
                float[] vector = new float[config.getDimension()];
                for (int i = 0; i < vector.length; i++) {
                    JsonNode coordinate = embedding.get(i);
                    if (!coordinate.isNumber()) { throw protocolException(); }
                    float number = coordinate.floatValue();
                    if (Float.isNaN(number) || Float.isInfinite(number)) { throw protocolException(); }
                    vector[i] = number;
                }
                ordered[position] = vector;
            }
            List<float[]> result = new ArrayList<float[]>(expectedCount);
            for (float[] vector : ordered) {
                if (vector == null) { throw protocolException(); }
                result.add(vector);
            }
            return new EmbeddingResponse(result);
        } catch (ClaraException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw protocolException();
        } catch (java.io.IOException exception) {
            throw protocolException();
        }
    }

    private void credentialUnavailable() {
        throw error(ErrorCodes.MODEL_CREDENTIAL_UNAVAILABLE, ErrorCategory.CONFIGURATION, false, null);
    }
    private void resourceLimit() {
        throw error(ErrorCodes.MODEL_REQUEST_TOO_LARGE, ErrorCategory.RESOURCE_LIMIT, false, null);
    }
    private void protocol() { throw protocolException(); }
    private ClaraException protocolException() {
        return error(ErrorCodes.MODEL_PROTOCOL_ERROR, ErrorCategory.EXTERNAL_SERVICE, false, null);
    }
    private void providerFailure(int status) {
        ErrorCategory category = status == 401 ? ErrorCategory.AUTHENTICATION
                : status == 403 ? ErrorCategory.AUTHORIZATION
                : status == 429 ? ErrorCategory.RATE_LIMIT : ErrorCategory.EXTERNAL_SERVICE;
        throw error(ErrorCodes.MODEL_PROVIDER_FAILED, category,
                status == 429 || status >= 500, String.valueOf(status));
    }
    private ClaraException error(String code, ErrorCategory category, boolean retryable,
            String providerCode) {
        return new ClaraException(new ClaraError(code, category, code, retryable, null,
                config.getProvider().name(), providerCode, null,
                Collections.<String, String>emptyMap()));
    }
}
