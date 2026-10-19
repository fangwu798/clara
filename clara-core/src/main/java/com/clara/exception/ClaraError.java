package com.clara.exception;

import com.clara.enums.ErrorCategoryEnum;
import com.clara.utils.StringUtils;
import lombok.Data;

import java.io.Serializable;
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
 * <p>字段与取值来源：错误码取自 {@link ErrorCodes}，分类取自 {@link ErrorCategoryEnum}；是否可重试由构造方
 * 显式给出，不从分类推导。实例不可变，可跨线程传递。
 */
@Data
public final class ClaraError implements Serializable {

    /** 序列化版本号。 */
    private static final long serialVersionUID = 1L;

    /**
     * 构造「调用已被取消」的错误，供取消令牌在终止调用时使用。
     *
     * @param traceId 调用跟踪标识，可为 null
     * @return 错误码为 {@link ErrorCodes#INVOCATION_CANCELLED}、分类为 {@link ErrorCategoryEnum#CANCELLED}
     * 且不可重试的错误，非 null
     */
    public static ClaraError cancelled(String traceId) {
        return builder(ErrorCodes.INVOCATION_CANCELLED, ErrorCategoryEnum.CANCELLED)
                .traceId(traceId)
                .build();
    }

    /**
     * 稳定错误码，非 null 且非空白，取值见 {@link ErrorCodes}。
     */
    private final String code;
    /**
     * 错误分类，非 null。
     */
    private final ErrorCategoryEnum category;
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
     * 由构建器创建领域错误。
     *
     * <p>兜底规则：{@code safeMessage} 为 null 或等于 {@code code} 时改用 {@link ErrorMessages#of(String)}
     * 生成中文提示；{@code safeDetails} 为 null 时置为空 map，否则拷贝成不可变映射。
     *
     * @param builder 已填充必填字段的构建器，不能为 null
     * @throws IllegalArgumentException code 为 null 或空白，或 category 为 null 时
     */
    private ClaraError(Builder builder) {
        if (StringUtils.isBlank(builder.code)) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (builder.category == null) {
            throw new IllegalArgumentException("category must not be null");
        }
        this.code = builder.code;
        this.category = builder.category;
        this.safeMessage = builder.safeMessage == null || builder.safeMessage.equals(builder.code)
                ? ErrorMessages.of(builder.code) : builder.safeMessage;
        this.retryable = builder.retryable;
        this.traceId = builder.traceId;
        this.provider = builder.provider;
        this.providerCode = builder.providerCode;
        this.retryAfterMillis = builder.retryAfterMillis;
        this.safeDetails = builder.safeDetails == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(builder.safeDetails));
    }

    /**
     * 创建领域错误构建器。
     *
     * @param code     稳定错误码，不可为 null 或空白
     * @param category 错误分类，不可为 null
     * @return 构建器，非 null
     */
    public static Builder builder(String code, ErrorCategoryEnum category) {
        return new Builder(code, category);
    }

    /**
     * 领域错误构建器；必填字段在工厂方法中传入，其余字段按需设置。
     */
    public static final class Builder {

        private final String code;
        private final ErrorCategoryEnum category;
        private String safeMessage;
        private boolean retryable;
        private String traceId;
        private String provider;
        private String providerCode;
        private Long retryAfterMillis;
        private Map<String, String> safeDetails;

        private Builder(String code, ErrorCategoryEnum category) {
            this.code = code;
            this.category = category;
        }

        public Builder safeMessage(String safeMessage) {
            this.safeMessage = safeMessage;
            return this;
        }

        public Builder retryable(boolean retryable) {
            this.retryable = retryable;
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        public Builder providerCode(String providerCode) {
            this.providerCode = providerCode;
            return this;
        }

        public Builder safeDetails(Map<String, String> safeDetails) {
            this.safeDetails = safeDetails;
            return this;
        }

        public ClaraError build() {
            return new ClaraError(this);
        }
    }
}
