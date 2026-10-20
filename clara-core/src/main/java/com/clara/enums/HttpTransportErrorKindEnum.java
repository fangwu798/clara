package com.clara.enums;

/**
 * HTTP 传输失败类别：只描述传输层的失败原因，由调用方映射为各自领域错误码。
 */
public enum HttpTransportErrorKindEnum {

    /** 目标端点不在允许列表中。 */
    ENDPOINT_DENIED,

    /** 请求体超过大小限制。 */
    REQUEST_TOO_LARGE,

    /** 响应体超过大小限制。 */
    RESPONSE_TOO_LARGE,

    /** 请求或读取超时。 */
    TIMEOUT,

    /** 传输层 IO 错误。 */
    IO_ERROR,

    /** 协议解析错误。 */
    PROTOCOL_ERROR,

    /** 本次调用已被取消。 */
    CANCELLED,

    /** 传输通道已关闭。 */
    CLOSED
}
