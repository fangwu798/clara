package com.clara.utils;

import com.clara.exception.ClaraError;
import com.clara.exception.ClaraException;
import com.clara.exception.ErrorCategory;
import com.clara.exception.ErrorCodes;
import com.clara.exception.ErrorMessages;
import com.clara.enums.ModelProviderEnum;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 集中创建模型调用链路的领域异常。
 *
 * <p>定位：错误码 → 错误类别 / 是否可重试 / 中文消息的映射集中在此，供传输器、SSE 解析器、
 * 编解码器与平台适配器共用，避免各类各写一份私有的 fail / protocol / capability 方法。
 *
 * <p>使用方法：
 * <ul>
 *   <li>无需实例化——构造器私有，全部方法为 {@code static}；</li>
 *   <li>本类**只构造、不抛出**：调用方需显式写 {@code throw ExceptionUtils.protocolError();}，
 *       让错误码与可重试语义在调用点可见；</li>
 *   <li>按场景选语义化工厂（如 {@link #requestTimeout()}、{@link #providerFailure(ModelProviderEnum, int)}）；
 *       需要归因到具体平台时使用带 {@link ModelProviderEnum} 参数的重载；</li>
 *   <li>新增错误场景时先在此补语义化工厂，不要在业务类里直接拼装 {@link ClaraError}。</li>
 * </ul>
 *
 * <p>线程安全：无状态，可并发调用；返回值每次都是新建实例，调用方可安全持有。
 *
 * <p>失败语义：方法自身不抛异常，返回的 {@link ClaraException} 携带错误码、类别、可重试标记与
 * 中文消息（消息模板取自 {@link ErrorMessages}，未登记的码退化为错误码本身）。
 */
public final class ExceptionUtils {

    /**
     * 平台信息缺失时写入消息的占位文案。
     */
    private static final String UNKNOWN = "未知";

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
    public static ClaraException requestTooLarge(ModelProviderEnum provider) {
        return fail(ErrorCodes.MODEL_REQUEST_TOO_LARGE, ErrorCategory.RESOURCE_LIMIT, false, provider);
    }

    /**
     * 构造「当前传输器不支持该媒体调用」的异常。
     *
     * <p>典型场景：媒体接口需要上传文件或接收二进制响应，而配置的传输器只支持 JSON 往返。
     *
     * @param provider 平台，可为 null（表示不归因到具体平台）
     * @return 配置类、不可重试的异常
     */
    public static ClaraException mediaTransportUnsupported(ModelProviderEnum provider) {
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
    public static ClaraException protocolError(ModelProviderEnum provider) {
        return fail(ErrorCodes.MODEL_PROTOCOL_ERROR, ErrorCategory.EXTERNAL_SERVICE, false, provider);
    }

    /**
     * 构造「平台不支持该能力」的异常。
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
    public static ClaraException credentialUnavailable(ModelProviderEnum provider) {
        return fail(ErrorCodes.MODEL_CREDENTIAL_UNAVAILABLE, ErrorCategory.CONFIGURATION, false, provider);
    }

    /**
     * 按 HTTP 状态码构造「平台调用失败」的异常。
     *
     * <p>归类规则：401 归鉴权、403 归授权、429 归限流、其余归外部服务；429 与 5xx 标记为可重试。
     * 状态码写入 providerCode 字段保留原始信息，便于排查；响应体不会被写入异常，避免泄露平台返回内容。
     *
     * @param provider 平台，可为 null（表示不归因到具体平台）
     * @param status   平台返回的 HTTP 状态码
     * @return 映射后的异常
     */
    public static ClaraException providerFailure(ModelProviderEnum provider, int status) {
        return providerFailure(provider, status, null);
    }

    /**
     * 按 HTTP 状态码与平台返回的错误提示构造「平台调用失败」的异常。
     *
     * <p>与 {@link #providerFailure(ModelProviderEnum, int)} 的区别：额外把平台错误响应体里的提示文本写入
     * {@code safeDetails} 的 {@code providerMessage} 键，供上层展示具体失败原因（如余额不足、参数非法）。
     * 归类与可重试规则不变（401 鉴权、403 授权、429 限流、其余外部服务；429 与 5xx 可重试），状态码仍写入
     * providerCode 与消息模板。
     *
     * @param provider        平台，可为 null（表示不归因到具体平台）
     * @param status          平台返回的 HTTP 状态码
     * @param providerMessage 平台返回的错误提示文本，可为 null（不写入细节）
     * @return 映射后的异常
     */
    public static ClaraException providerFailure(ModelProviderEnum provider, int status, String providerMessage) {
        ErrorCategory category = status == 401 ? ErrorCategory.AUTHENTICATION : status == 403 ? ErrorCategory.AUTHORIZATION : status == 429 ? ErrorCategory.RATE_LIMIT : ErrorCategory.EXTERNAL_SERVICE;
        Map<String, String> details = new LinkedHashMap<String, String>();
        if (providerMessage != null && !providerMessage.trim().isEmpty()) {
            details.put("providerMessage", providerMessage.trim());
        }
        return new ClaraException(new ClaraError(ErrorCodes.MODEL_PROVIDER_FAILED, category,
                message(ErrorCodes.MODEL_PROVIDER_FAILED, provider, String.valueOf(status)),
                status == 429 || status >= 500, null, provider == null ? null : provider.name(),
                String.valueOf(status), null, details));
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
    public static ClaraException providerEventFailure(ModelProviderEnum provider, String providerCode) {
        return fail(ErrorCodes.MODEL_PROVIDER_FAILED, ErrorCategory.EXTERNAL_SERVICE, false, provider, providerCode);
    }

    /**
     * 按平台在事件流中自报的错误构造异常，并把平台返回的错误提示写入安全细节。
     *
     * <p>与 {@link #providerEventFailure(ModelProviderEnum, String)} 的区别：平台错误码仍写入 providerCode 便于
     * 排查；平台返回的提示文本写入 {@code safeDetails} 的 {@code providerMessage} 键，供上层展示具体的失败原因
     * （如限流、并发超限等），不进入面向使用者的默认文案，避免把不可控的平台文本当成本地文案。
     *
     * @param provider        平台，可为 null（表示不归因到具体平台）
     * @param providerCode    平台侧原始错误码，可为 null
     * @param providerMessage 平台侧返回的错误提示文本，可为 null
     * @return 外部服务类、不可重试的异常
     */
    public static ClaraException providerEventFailure(ModelProviderEnum provider, String providerCode, String providerMessage) {
        Map<String, String> details = new LinkedHashMap<String, String>();
        if (providerMessage != null && !providerMessage.trim().isEmpty()) {
            details.put("providerMessage", providerMessage);
        }
        return new ClaraException(new ClaraError(
                ErrorCodes.MODEL_PROVIDER_FAILED,
                ErrorCategory.EXTERNAL_SERVICE,
                message(ErrorCodes.MODEL_PROVIDER_FAILED, provider, providerCode),
                false,
                null,
                provider == null ? null : provider.name(),
                providerCode,
                null,
                details));
    }

    /**
     * 按错误码、类别与可重试标记构造模型调用异常。
     *
     * <p>消息由 {@link ErrorMessages} 按错误码生成中文提示，未登记的错误码退化为错误码本身，
     * 便于新增错误码时保持可用。
     *
     * @param code      错误码
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
     * <p>消息由 {@link ErrorMessages} 生成，模板中的平台占位符用平台名填充；平台名同时写入错误的
     * provider 字段，便于按平台归因。
     *
     * @param code      错误码
     * @param category  错误类别
     * @param retryable 是否可由上层重试
     * @param provider  平台，可为 null
     * @return 模型调用异常
     */
    public static ClaraException fail(String code, ErrorCategory category, boolean retryable,
                                      ModelProviderEnum provider) {
        return fail(code, category, retryable, provider, null);
    }

    /**
     * 按错误码、类别、可重试标记、平台与平台原始错误码构造模型调用异常。
     *
     * <p>消息由 {@link ErrorMessages} 生成，模板中的平台与平台码占位符分别用 provider 与 providerCode 填充；
     * 两者同时写入错误的对应字段，便于归因与排查。平台或平台码缺失时，消息中显示为「未知」。
     *
     * @param code         错误码
     * @param category     错误类别
     * @param retryable    是否可由上层重试
     * @param provider     平台，可为 null
     * @param providerCode 平台侧原始错误标识，可为 null
     * @return 模型调用异常
     */
    public static ClaraException fail(String code, ErrorCategory category, boolean retryable,
                                      ModelProviderEnum provider, String providerCode) {
        return new ClaraException(new ClaraError(code, category, message(code, provider, providerCode), retryable,
                null, provider == null ? null : provider.name(), providerCode, null,
                Collections.<String, String>emptyMap()));
    }

    /**
     * 按错误码与平台上下文生成中文提示。
     *
     * @param code         错误码
     * @param provider     平台，可为 null（消息中显示为「未知」）
     * @param providerCode 平台侧原始错误标识，可为 null（消息中显示为「未知」）
     * @return 中文提示；错误码未登记时返回错误码本身
     */
    private static String message(String code, ModelProviderEnum provider, String providerCode) {
        Map<String, String> args = new LinkedHashMap<String, String>();
        args.put("provider", provider == null ? UNKNOWN : provider.name());
        args.put("providerCode", providerCode == null || providerCode.trim().isEmpty() ? UNKNOWN : providerCode);
        return ErrorMessages.of(code, args);
    }
}
