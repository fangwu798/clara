package com.clara.kernel;

/**
 * 定义 Clara 错误的分类。
 */
public enum ErrorCategory {
    CONFIGURATION,
    VALIDATION,
    AUTHENTICATION,
    AUTHORIZATION,
    CAPABILITY,
    RATE_LIMIT,
    TIMEOUT,
    CANCELLED,
    CONFLICT,
    RESOURCE_LIMIT,
    CONTENT_SAFETY,
    EXTERNAL_SERVICE,
    UNKNOWN_SIDE_EFFECT,
    INTERNAL
}
