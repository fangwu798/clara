package com.clara.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 表示一次领域调用失败的结构化信息。
 *
 * <p>是什么、在哪一层：内核异常包（{@code com.clara.kernel.exception}）中的不可变值对象，是
 * {@link ClaraException#getError()} 的返回值；各模块的错误工厂构造它，再交给 {@link ClaraException} 抛出。
 *
 * <p>约定：{@link #safeMessage} 在调用方未给出、或直接把错误码当消息传入时，统一由 {@link ErrorMessages}
 * 按错误码生成中文提示，因此各模块沿用「消息取错误码」的旧写法也能得到可读文案；错误码本身仍由
 * {@link #getCode()} 承载，按码分支的逻辑不受影响。
 *
 * <p>字段与取值来源：错误码取自 {@link ErrorCodes}，分类取自 {@link ErrorCategory}；是否可重试由构造方
 * 显式给出，不从分类推导。实例不可变，可跨线程传递。
 */
public final class ClaraError {
    /**
     * 构造只带错误码、分类与跟踪标识的错误，其余字段为 null 或空 map。
     *
     * @param code     稳定错误码，不可为 null 或空白
     * @param category 错误分类，不可为 null
     * @param traceId  调用跟踪标识，可为 null
     * @return 结构化错误，非 null
     */
    private static ClaraError simple(String code, ErrorCategory category, String traceId) {
        return new ClaraError(code, category, null, false, traceId,
                null, null, null, Collections.emptyMap());
    }
    /**
     * 构造「调用已被取消」的错误，供取消令牌在终止调用时使用。
     *
     * @param traceId 调用跟踪标识，可为 null
     * @return 错误码为 {@link ErrorCodes#INVOCATION_CANCELLED}、分类为 {@link ErrorCategory#CANCELLED}
     *         且不可重试的错误，非 null
     */
    public static ClaraError cancelled(String traceId) {
        return simple(ErrorCodes.INVOCATION_CANCELLED, ErrorCategory.CANCELLED, traceId);
    }
    /**
     * 稳定错误码，非 null 且非空白，取值见 {@link ErrorCodes}。
     */
    private final String code;
    /**
     * 错误分类，非 null。
     */
    private final ErrorCategory category;
    /**
     * 面向使用者的安全文案，非 null；未显式给出或直接传入错误码时由 {@link ErrorMessages} 按错误码生成。
     */
    private final String safeMessage;
    /**
     * 该错误是否可重试，由构造错误的一方给出。
     */
    private final boolean retryable;
    /**
     * 调用跟踪标识，可为 null。
     */
    private final String traceId;
    /**
     * 出错的平台名，可为 null。
     */
    private final String provider;
    /**
     * 平台返回的错误码，可为 null。
     */
    private final String providerCode;
    /**
     * 建议的重试等待毫秒数，未知时为 null。
     */
    private final Long retryAfterMillis;
    /**
     * 可安全外发的错误细节，非 null、不可变；无细节时为空 map。
     */
    private final Map<String, String> safeDetails;

    /**
     * 创建领域错误。
     *
     * <p>兜底规则：{@code safeMessage} 为 null 或等于 {@code code} 时改用 {@link ErrorMessages#of(String)}
     * 生成中文提示；{@code safeDetails} 为 null 时置为空 map，否则拷贝成不可变映射。
     *
     * @param code             稳定错误码，不可为 null 或空白
     * @param category         错误分类，不可为 null
     * @param safeMessage      面向使用者的文案，可为 null
     * @param retryable        是否可重试
     * @param traceId          调用跟踪标识，可为 null
     * @param provider         出错的平台名，可为 null
     * @param providerCode     平台返回的错误码，可为 null
     * @param retryAfterMillis 建议的重试等待毫秒数，可为 null
     * @param safeDetails      可安全外发的错误细节，可为 null
     * @throws IllegalArgumentException code 为 null 或空白，或 category 为 null 时
     */
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
    /**
     * 读取稳定错误码。
     *
     * @return 错误码，非 null 且非空白
     */
    public String getCode() { return code; }
    /**
     * 读取错误分类。
     *
     * @return 错误分类，非 null
     */
    public ErrorCategory getCategory() { return category; }
    /**
     * 读取面向使用者的中文文案。
     *
     * @return 中文文案，非 null
     */
    public String getSafeMessage() { return safeMessage; }
    /**
     * 读取该错误是否可重试。
     *
     * @return 可重试时为 true
     */
    public boolean isRetryable() { return retryable; }
    /**
     * 读取调用跟踪标识。
     *
     * @return 跟踪标识，可为 null
     */
    public String getTraceId() { return traceId; }
    /**
     * 读取出错的平台名。
     *
     * @return 平台名，可为 null
     */
    public String getProvider() { return provider; }
    /**
     * 读取平台返回的错误码。
     *
     * @return 平台错误码，可为 null
     */
    public String getProviderCode() { return providerCode; }
    /**
     * 读取建议的重试等待毫秒数。
     *
     * @return 等待毫秒数，未知时为 null
     */
    public Long getRetryAfterMillis() { return retryAfterMillis; }
    /**
     * 读取可安全外发的错误细节。
     *
     * @return 错误细节，非 null、不可变；无细节时为空 map
     */
    public Map<String, String> getSafeDetails() { return safeDetails; }
}
