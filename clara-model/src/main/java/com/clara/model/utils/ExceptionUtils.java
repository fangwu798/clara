package com.clara.model.utils;

import com.clara.kernel.ClaraError;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCategory;
import com.clara.kernel.ErrorCodes;

import java.util.Collections;

/**
 * 集中创建模型调用链路的领域异常。
 *
 * <p>用途：把服务提供商 HTTP/SSE 调用中反复出现的错误码、错误类别与是否可重试的映射集中到一处，
 * 供传输器、SSE 解析器与适配器共用，避免每个类各写一份私有的 fail/tooLarge/protocol 等方法。
 *
 * <p>使用规范：
 * <ul>
 *   <li>本类只负责构造异常、不负责抛出，调用方需显式使用 {@code throw ExceptionUtils.protocolError();}，
 *       这样错误码与是否可重试的语义在调用点一目了然。</li>
 *   <li>返回值每次都是新建实例，可在多线程中安全使用同一工厂方法。</li>
 *   <li>新增错误场景时优先在此补充语义化工厂方法，不要在业务类里直接拼装 {@link ClaraError}。</li>
 * </ul>
 */
public final class ExceptionUtils {

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
     * 构造传输层 IO 失败（连接中断、读取失败等）的异常。
     *
     * @return 外部服务类、可重试的异常
     */
    public static ClaraException transportIoError() {
        return fail(ErrorCodes.MODEL_TRANSPORT_IO_ERROR, ErrorCategory.EXTERNAL_SERVICE, true);
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
        return new ClaraException(new ClaraError(code, category, code, retryable, null,
                null, null, null, Collections.<String, String>emptyMap()));
    }
}
