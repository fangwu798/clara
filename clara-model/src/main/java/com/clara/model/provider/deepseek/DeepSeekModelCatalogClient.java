package com.clara.model.provider.deepseek;

import com.clara.kernel.ClaraError;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCategory;
import com.clara.kernel.ErrorCodes;
import com.clara.kernel.InvocationContext;
import com.clara.model.api.ModelProvider;
import com.clara.model.provider.http.ProviderCredentialResolver;
import com.clara.model.provider.http.ProviderHttpRequest;
import com.clara.model.provider.http.ProviderHttpResponse;
import com.clara.model.provider.http.ProviderHttpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 提供访问DeepSeek模型目录的客户端。
 */
@Data
public final class DeepSeekModelCatalogClient {
    private final DeepSeekModelCatalogConfig config;
    private final ProviderHttpTransport transport;
    private final ProviderCredentialResolver credentials;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    public DeepSeekModelCatalogClient(DeepSeekModelCatalogConfig config,
            ProviderHttpTransport transport, ProviderCredentialResolver credentials, Clock clock) {
        if (config == null || transport == null || credentials == null || clock == null) {
            throw new IllegalArgumentException("dependencies must not be null");
        }
        this.config = config;
        this.transport = transport;
        this.credentials = credentials;
        this.clock = clock;
    }

    public DeepSeekModelAvailabilitySnapshot fetch(InvocationContext context) {
        if (context == null) { throw new IllegalArgumentException("context must not be null"); }
        context.getCancellationToken().throwIfCancellationRequested(context.getTraceId());
        char[] resolved = credentials.resolve(context.getTenantId(), config.getSecretRef());
        if (resolved == null || resolved.length == 0) { throw error(
                ErrorCodes.MODEL_CREDENTIAL_UNAVAILABLE, ErrorCategory.CONFIGURATION, false, null); }
        char[] secret = resolved.clone();
        try {
            Map<String, String> headers = new LinkedHashMap<String, String>();
            headers.put("Authorization", "Bearer " + new String(secret));
            headers.put("Accept", "application/json");
            ProviderHttpResponse response = transport.execute(new ProviderHttpRequest("GET",
                    config.getEndpoint(), headers, null), context);
            if (response == null) { throw protocol(); }
            if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
                throw providerFailure(response.getStatusCode());
            }
            return decode(response.getBody());
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    private DeepSeekModelAvailabilitySnapshot decode(byte[] body) {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode data = root == null ? null : root.get("data");
            if (root == null || !root.isObject() || !"list".equals(text(root.get("object")))
                    || data == null || !data.isArray()) { throw protocol(); }
            if (data.size() > config.getMaxModels()) { throw tooLarge(); }
            List<DeepSeekAvailableModel> models = new ArrayList<DeepSeekAvailableModel>();
            Set<String> ids = new HashSet<String>();
            for (JsonNode item : data) {
                if (item == null || !item.isObject()) { throw protocol(); }
                String id = text(item.get("id"));
                String object = text(item.get("object"));
                String ownedBy = optionalText(item.get("owned_by"));
                if (invalidId(id) || !"model".equals(object) || !ids.add(id)) { throw protocol(); }
                if (ownedBy != null && (ownedBy.length() > config.getMaxModelIdChars()
                        || containsControl(ownedBy))) { throw protocol(); }
                models.add(new DeepSeekAvailableModel(id, ownedBy));
            }
            Collections.sort(models);
            return new DeepSeekModelAvailabilitySnapshot(config.getApiProfile(),
                    clock.millis(), models);
        } catch (ClaraException exception) {
            throw exception;
        } catch (IOException exception) {
            throw protocol();
        } catch (RuntimeException exception) {
            throw protocol();
        }
    }

    private boolean invalidId(String value) {
        return value == null || value.trim().isEmpty()
                || value.length() > config.getMaxModelIdChars() || containsControl(value);
    }

    private static boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return true;
        return false;
    }

    private static String text(JsonNode value) {
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static String optionalText(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) { throw protocol(); }
        return value.textValue();
    }

    private ClaraException providerFailure(int status) {
        ErrorCategory category = status == 401 ? ErrorCategory.AUTHENTICATION
                : status == 403 ? ErrorCategory.AUTHORIZATION
                : status == 429 ? ErrorCategory.RATE_LIMIT : ErrorCategory.EXTERNAL_SERVICE;
        return error(ErrorCodes.MODEL_PROVIDER_FAILED, category,
                status == 429 || status >= 500, String.valueOf(status));
    }

    private static ClaraException tooLarge() {
        return error(ErrorCodes.MODEL_RESPONSE_TOO_LARGE, ErrorCategory.RESOURCE_LIMIT, false, null);
    }

    private static ClaraException protocol() {
        return error(ErrorCodes.MODEL_PROTOCOL_ERROR, ErrorCategory.EXTERNAL_SERVICE, false, null);
    }

    private static ClaraException error(String code, ErrorCategory category, boolean retryable,
            String providerCode) {
        return new ClaraException(new ClaraError(code, category, code, retryable, null,
                ModelProvider.DEEPSEEK.name(), providerCode, null,
                Collections.<String, String>emptyMap()));
    }
}
