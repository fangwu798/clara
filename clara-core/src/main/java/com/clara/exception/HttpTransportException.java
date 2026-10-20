package com.clara.exception;


import com.clara.enums.HttpTransportErrorKindEnum;
import lombok.Getter;

/**
 * 通用 HTTP 传输异常：只表达传输层失败类别，不绑定模型或 MCP 的错误码。
 *
 * <p>调用方（模型适配器、MCP 传输）负责把 {@link HttpTransportErrorKindEnum} 映射为各自领域错误码，
 * 从而保持对外错误契约不变。
 */
@Getter
public final class HttpTransportException extends RuntimeException {

    /**
     * -- GETTER --
     *  返回失败类别。
     *
     * @return 失败类别，非 null
     */
    private final HttpTransportErrorKindEnum kind;

    /**
     * 创建传输异常。
     *
     * @param kind    失败类别，不可为 null
     * @param message 错误描述，不可为 null
     */
    public HttpTransportException(HttpTransportErrorKindEnum kind, String message) {
        this(kind, message, null);
    }

    /**
     * 创建带原因的传输异常。
     *
     * @param kind    失败类别，不可为 null
     * @param message 错误描述，不可为 null
     * @param cause   原始异常，可为 null
     */
    public HttpTransportException(HttpTransportErrorKindEnum kind, String message, Throwable cause) {
        super(message, cause);
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        this.kind = kind;
    }

    /**
     * 判断该失败是否适合重试。
     *
     * @return 超时或 IO 错误返回 true，其余返回 false
     */
    public boolean isRetryable() {
        return kind == HttpTransportErrorKindEnum.TIMEOUT || kind == HttpTransportErrorKindEnum.IO_ERROR;
    }

    public static HttpTransportException endpointDenied() {
        return new HttpTransportException(HttpTransportErrorKindEnum.ENDPOINT_DENIED, "endpoint is not allowed");
    }

    public static HttpTransportException requestTooLarge() {
        return new HttpTransportException(HttpTransportErrorKindEnum.REQUEST_TOO_LARGE, "request body is too large");
    }

    public static HttpTransportException responseTooLarge() {
        return new HttpTransportException(HttpTransportErrorKindEnum.RESPONSE_TOO_LARGE, "response body is too large");
    }

    public static HttpTransportException timeout() {
        return new HttpTransportException(HttpTransportErrorKindEnum.TIMEOUT, "request timed out");
    }

    public static HttpTransportException ioError(Throwable cause) {
        return new HttpTransportException(HttpTransportErrorKindEnum.IO_ERROR, "transport io error", cause);
    }

    public static HttpTransportException protocolError(String message) {
        return new HttpTransportException(HttpTransportErrorKindEnum.PROTOCOL_ERROR, message);
    }

    public static HttpTransportException cancelled() {
        return new HttpTransportException(HttpTransportErrorKindEnum.CANCELLED, "request was cancelled");
    }

    public static HttpTransportException closed() {
        return new HttpTransportException(HttpTransportErrorKindEnum.CLOSED, "transport is closed");
    }
}
