package com.clara.exception;


/**
 * 通用 HTTP 传输异常：只表达传输层失败类别，不绑定模型或 MCP 的错误码。
 *
 * <p>调用方（模型适配器、MCP 传输）负责把 {@link Kind} 映射为各自领域错误码，
 * 从而保持对外错误契约不变。
 */
public final class HttpTransportException extends RuntimeException {

    /**
     * 传输失败类别。
     */
    public enum Kind {
        ENDPOINT_DENIED,
        REQUEST_TOO_LARGE,
        RESPONSE_TOO_LARGE,
        TIMEOUT,
        IO_ERROR,
        PROTOCOL_ERROR,
        CANCELLED,
        CLOSED
    }


    private final Kind kind;

    /**
     * 创建传输异常。
     *
     * @param kind    失败类别，不可为 null
     * @param message 错误描述，不可为 null
     */
    public HttpTransportException(Kind kind, String message) {
        this(kind, message, null);
    }

    /**
     * 创建带原因的传输异常。
     *
     * @param kind    失败类别，不可为 null
     * @param message 错误描述，不可为 null
     * @param cause   原始异常，可为 null
     */
    public HttpTransportException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        this.kind = kind;
    }

    /**
     * 返回失败类别。
     *
     * @return 失败类别，非 null
     */
    public Kind getKind() {
        return kind;
    }

    /**
     * 判断该失败是否适合重试。
     *
     * @return 超时或 IO 错误返回 true，其余返回 false
     */
    public boolean isRetryable() {
        return kind == Kind.TIMEOUT || kind == Kind.IO_ERROR;
    }

    public static HttpTransportException endpointDenied() {
        return new HttpTransportException(Kind.ENDPOINT_DENIED, "endpoint is not allowed");
    }

    public static HttpTransportException requestTooLarge() {
        return new HttpTransportException(Kind.REQUEST_TOO_LARGE, "request body is too large");
    }

    public static HttpTransportException responseTooLarge() {
        return new HttpTransportException(Kind.RESPONSE_TOO_LARGE, "response body is too large");
    }

    public static HttpTransportException timeout() {
        return new HttpTransportException(Kind.TIMEOUT, "request timed out");
    }

    public static HttpTransportException ioError(Throwable cause) {
        return new HttpTransportException(Kind.IO_ERROR, "transport io error", cause);
    }

    public static HttpTransportException protocolError(String message) {
        return new HttpTransportException(Kind.PROTOCOL_ERROR, message);
    }

    public static HttpTransportException cancelled() {
        return new HttpTransportException(Kind.CANCELLED, "request was cancelled");
    }

    public static HttpTransportException closed() {
        return new HttpTransportException(Kind.CLOSED, "transport is closed");
    }
}
