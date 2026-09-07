package com.clara.kernel;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 表示。
 */
public final class ClaraError {
    private static ClaraError simple(String code, ErrorCategory category, String traceId) {
        return new ClaraError(code, category, code, false, traceId,
                null, null, null, Collections.emptyMap());
    }
    public static ClaraError cancelled(String traceId) {
        return simple(ErrorCodes.INVOCATION_CANCELLED, ErrorCategory.CANCELLED, traceId);
    }
    private final String code;
    private final ErrorCategory category;
    private final String safeMessage;
    private final boolean retryable;
    private final String traceId;
    private final String provider;
    private final String providerCode;
    private final Long retryAfterMillis;
    private final Map<String, String> safeDetails;

    public ClaraError(String code, ErrorCategory category, String safeMessage,
            boolean retryable, String traceId, String provider, String providerCode,
            Long retryAfterMillis, Map<String, String> safeDetails) {
        if (code == null || code.trim().isEmpty()) { throw new IllegalArgumentException("code must not be blank"); }
        if (category == null) { throw new IllegalArgumentException("category must not be null"); }
        this.code = code;
        this.category = category;
        this.safeMessage = safeMessage == null ? code : safeMessage;
        this.retryable = retryable;
        this.traceId = traceId;
        this.provider = provider;
        this.providerCode = providerCode;
        this.retryAfterMillis = retryAfterMillis;
        this.safeDetails = safeDetails == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(safeDetails));
    }
    public String getCode() { return code; }
    public ErrorCategory getCategory() { return category; }
    public String getSafeMessage() { return safeMessage; }
    public boolean isRetryable() { return retryable; }
    public String getTraceId() { return traceId; }
    public String getProvider() { return provider; }
    public String getProviderCode() { return providerCode; }
    public Long getRetryAfterMillis() { return retryAfterMillis; }
    public Map<String, String> getSafeDetails() { return safeDetails; }
}
