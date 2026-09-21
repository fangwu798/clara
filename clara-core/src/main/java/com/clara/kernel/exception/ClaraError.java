package com.clara.kernel.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 表示一次领域调用失败的结构化信息。
 *
 * <p>约定：{@link #safeMessage} 在调用方未给出、或直接把错误码当消息传入时，统一由 {@link ErrorMessages}
 * 按错误码生成中文提示，因此各模块沿用「消息取错误码」的旧写法也能得到可读文案；错误码本身仍由
 * {@link #getCode()} 承载，按码分支的逻辑不受影响。
 */
public final class ClaraError {
    private static ClaraError simple(String code, ErrorCategory category, String traceId) {
        return new ClaraError(code, category, null, false, traceId,
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
        this.safeMessage = safeMessage == null || safeMessage.equals(code)
                ? ErrorMessages.of(code)
                : safeMessage;
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
