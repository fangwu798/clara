package com.clara.kernel.exception;

/**
 * Clara 错误的分类：表示失败的性质，供上层决定如何处置（提示、降级或重试）。
 *
 * <p>是什么、在哪一层：内核异常包（{@code com.clara.kernel.exception}）中的值类型，是
 * {@link ClaraError#getCategory()} 的取值；分类本身不决定能否重试——是否可重试由构造错误的一方通过
 * {@link ClaraError#isRetryable()} 显式给出。
 */
public enum ErrorCategory {
    /** 配置错误：配置项缺失或非法，如凭据不可用、平台未配置该能力。 */
    CONFIGURATION,
    /** 入参非法：调用方传入的参数不满足接口契约。 */
    VALIDATION,
    /** 鉴权失败：身份未被平台接受（如 401）。 */
    AUTHENTICATION,
    /** 授权失败：身份已知但无权访问（如 403、端点不在允许清单内）。 */
    AUTHORIZATION,
    /** 能力不受支持：当前平台或实现不提供请求的能力。 */
    CAPABILITY,
    /** 限流：请求频率或额度超过平台限制（如 429）。 */
    RATE_LIMIT,
    /** 超时：连接、读取或等待响应超过截止时间。 */
    TIMEOUT,
    /** 已取消：调用被协作式取消。 */
    CANCELLED,
    /** 冲突：基于旧版本写入，或当前状态不允许该操作。 */
    CONFLICT,
    /** 资源超限：体积、数量或规模超过配置上限。 */
    RESOURCE_LIMIT,
    /** 内容安全：内容安全校验判定为不可接受。 */
    CONTENT_SAFETY,
    /** 外部服务失败：平台或下游依赖返回错误或协议不符。 */
    EXTERNAL_SERVICE,
    /** 副作用未知：操作可能已生效但结果无法确认，不可直接重试。 */
    UNKNOWN_SIDE_EFFECT,
    /** 内部错误：本库自身的逻辑错误。 */
    INTERNAL
}
