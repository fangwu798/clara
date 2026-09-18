package com.clara.model.utils;

import com.clara.kernel.ClaraError;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCategory;
import com.clara.kernel.ErrorCodes;
import com.clara.model.api.ModelProvider;

import java.util.Collections;

/**
 * 集中创建模型调用链路的领域异常。
 *
 * <p>用途：把服务提供商 HTTP/SSE 调用与协议编解码中反复出现的错误码、错误类别与是否可重试的映射
 * 集中到一处，供传输器、SSE 解析器、编解码器与适配器共用，避免每个类各写一份私有的
 * fail/protocol/capability 等方法。
 *
 * <p>使用规范：
 * <ul>
 *   <li>本类只负责构造异常、不负责抛出，调用方需显式使用 {@code throw ExceptionUtils.protocolError();}，
 *       这样错误码与是否可重试的语义在调用点一目了然。</li>
 *   <li>返回值每次都是新建实例，可在多线程中安全使用同一工厂方法。</li>
 *   <li>需要把错误归因到具体平台时，使用带 {@link ModelProvider} 参数的重载。</li>
 *   <li>新增错误场景时优先在此补充语义化工厂方法，不要在业务类里直接拼装 {@link ClaraError}。</li>
 * </ul>
 */
public final class ExceptionUtils {
    private ExceptionUtils() {
    }

    /**
     * 构造端点不在白名单内的异常。
     *
     * @return 鉴权类、不可重试的异常
     */
    public static ClaraException endpointDenied() {
        return fail(ErrorCodes.MODEL_ENDPOINT_DENIED, ErrorCategory.AUTHORIZATION, false);
    }

    /**
     * 构造请求体超出配置上限的异常。
     *
     * @return 资源限制类、不可重试的异常
     */
    public static ClaraException requestTooLarge() {
        return fail(ErrorCodes.MODEL_REQUEST_TOO_LARGE, ErrorCategory.RESOURCE_LIMIT, false);
    }

    /**
     * 构造请求体超出配置上限、并归因到具体平台的异常。
     *
     * <p>媒体类接口（图片、语音、视频）的体积上限由各平台配置决定，超限时归因到平台便于排查。
     *
     * @param provider 平台，可为 null（表示不归因到具体平台）
     * @return 资源限制类、不可重试的异常
     */
    public static ClaraException requestTooLarge(ModelProvider provider) {
        return fail(ErrorCodes.MODEL_REQUEST_TOO_LARGE, ErrorCategory.RESOURCE_LIMIT, false, provider);
    }

    /**
     * 构造“当前传输器不支持该媒体调用”的异常。
     *
     * <p>典型场景：媒体接口需要上传文件或接收二进制响应，而配置的传输器只支持 JSON 往返。
     *
     * @param provider 平台，可为 null（表示不归因到具体平台）
     * @return 配置类、不可重试的异常
     */
    public static ClaraException mediaTransportUnsupported(ModelProvider provider) {
        return fail(ErrorCodes.MEDIA_TRANSPORT_NOT_SUPPORTED, ErrorCategory.CONFIGURATION, false, provider);
    }

    /**
     * 构造响应体或 SSE 事件超出配置上限的异常。
     *
     * @return 资源限制类、不可重试的异常
     */
    public static ClaraException responseTooLarge() {
        return fail(ErrorCodes.MODEL_RESPONSE_TOO_LARGE, ErrorCategory.RESOURCE_LIMIT, false);
    }

    /**
     * 构造请求超时（连接、读取或流式读取过程中超时）的异常。
     *
     * @return 超时类、可重试的异常
     */
    public static ClaraException requestTimeout() {
        return fail(ErrorCodes.MODEL_REQUEST_TIMEOUT, ErrorCategory.TIMEOUT, true);
    }

    /**
     * 构造服务提供商响应不符合预期协议的异常。
     *
     * @return 外部服务类、不可重试的异常
     */
    public static ClaraException protocolError() {
        return fail(ErrorCodes.MODEL_PROTOCOL_ERROR, ErrorCategory.EXTERNAL_SERVICE, false);
    }

    /**
     * 构造服务提供商响应不符合预期协议、并归因到具体平台的异常。
     *
     * <p>与 {@link #protocolError()} 的区别是把平台名写入错误的 provider 字段，便于按平台统计与排查。
     *
     * @param provider 平台，可为 null（表示不归因到具体平台）
     * @return 外部服务类、不可重试的异常
     */
    public static ClaraException protocolError(ModelProvider provider) {
        return fail(ErrorCodes.MODEL_PROTOCOL_ERROR, ErrorCategory.EXTERNAL_SERVICE, false, provider);
    }

    /**
     * 构造“平台不支持该能力”的异常。
     *
     * <p>典型场景：向兼容协议接口提交了它不支持的内容或参数（如媒体内容、{@code topK}）。
     *
     * @return 能力类、不可重试的异常
     */
    public static ClaraException capabilityUnsupported() {
        return fail(ErrorCodes.CAPABILITY_NOT_SUPPORTED, ErrorCategory.CAPABILITY, false);
    }

    /**
     * 构造传输层 IO 失败（连接中断、读取失败等）的异常。
     *
     * @return 外部服务类、可重试的异常
     */
    public static ClaraException transportIoError() {
        return fail(ErrorCodes.MODEL_TRANSPORT_IO_ERROR, ErrorCategory.EXTERNAL_SERVICE, true);
    }

    /**
     * 构造凭据不可用的异常。
     *
     * <p>典型场景：凭据解析器按租户解析后未返回任何密钥。这属于配置问题，重试无法解决，因此不可重试。
     *
     * @param provider 平台，可为 null（表示不归因到具体平台）
     * @return 配置类、不可重试的异常
     */
    public static ClaraException credentialUnavailable(ModelProvider provider) {
        return fail(ErrorCodes.MODEL_CREDENTIAL_UNAVAILABLE, ErrorCategory.CONFIGURATION, false, provider);
    }

    /**
     * 按 HTTP 状态码构造“平台调用失败”的异常。
     *
     * <p>归类规则：401 归鉴权、403 归授权、429 归限流、其余归外部服务；429 与 5xx 标记为可重试。
     * 状态码写入 providerCode 字段保留原始信息，便于排查；响应体不会被写入异常，避免泄露平台返回内容。
     *
     * @param provider 平台，可为 null（表示不归因到具体平台）
     * @param status   平台返回的 HTTP 状态码
     * @return 映射后的异常
     */
    public static ClaraException providerFailure(ModelProvider provider, int status) {
        ErrorCategory category = status == 401 ? ErrorCategory.AUTHENTICATION : status == 403 ? ErrorCategory.AUTHORIZATION : status == 429 ? ErrorCategory.RATE_LIMIT : ErrorCategory.EXTERNAL_SERVICE;
        return fail(ErrorCodes.MODEL_PROVIDER_FAILED, category, status == 429 || status >= 500, provider, String.valueOf(status));
    }

    /**
     * 按平台在事件流中自报的错误构造异常。
     *
     * <p>典型场景：流式接口在事件里下发 {@code error{code,message}}（如生图流中单张图片失败、整条流
     * 因参数非法而终止）。这类错误的可重试性无法从平台码推断，因此统一按外部服务、不可重试处理；
     * 平台原始错误码写入 providerCode 便于排查，平台返回的文本不入异常，避免泄露平台内容。
     *
     * @param provider     平台，可为 null（表示不归因到具体平台）
     * @param providerCode 平台侧原始错误码，可为 null
     * @return 外部服务类、不可重试的异常
     */
    public static ClaraException providerEventFailure(ModelProvider provider, String providerCode) {
        return fail(ErrorCodes.MODEL_PROVIDER_FAILED, ErrorCategory.EXTERNAL_SERVICE, false, provider, providerCode);
    }

    /**
     * 按错误码、类别与可重试标记构造模型调用异常。
     *
     * <p>消息取自错误码本身，避免把请求体、密钥等敏感内容写进异常信息。
     *
     * @param code      错误码，同时作为错误消息
     * @param category  错误类别
     * @param retryable 是否可由上层重试
     * @return 模型调用异常
     */
    public static ClaraException fail(String code, ErrorCategory category, boolean retryable) {
        return fail(code, category, retryable, null);
    }

    /**
     * 按错误码、类别、可重试标记与平台构造模型调用异常。
     *
     * <p>消息取自错误码本身；平台名会写入错误的 provider 字段，便于按平台归因。
     *
     * @param code      错误码，同时作为错误消息
     * @param category  错误类别
     * @param retryable 是否可由上层重试
     * @param provider  平台，可为 null
     * @return 模型调用异常
     */
    public static ClaraException fail(String code, ErrorCategory category, boolean retryable,
                                      ModelProvider provider) {
        return fail(code, category, retryable, provider, null);
    }

    /**
     * 按错误码、类别、可重试标记、平台与平台原始错误码构造模型调用异常。
     *
     * <p>消息取自错误码本身；平台名写入 provider 字段，平台侧的原始错误标识（如 HTTP 状态码）写入
     * providerCode 字段，两者都只用于归因与排查。
     *
     * @param code         错误码，同时作为错误消息
     * @param category     错误类别
     * @param retryable    是否可由上层重试
     * @param provider     平台，可为 null
     * @param providerCode 平台侧原始错误标识，可为 null
     * @return 模型调用异常
     */
    public static ClaraException fail(String code, ErrorCategory category, boolean retryable,
                                      ModelProvider provider, String providerCode) {
        return new ClaraException(new ClaraError(code, category, code, retryable, null,
                provider == null ? null : provider.name(), providerCode, null,
                Collections.<String, String>emptyMap()));
    }
}
