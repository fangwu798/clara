package com.clara.core.telemetry;

/**
 * 链路 Span 的终态。
 *
 * <p>只表达「这次阶段调用是否按预期结束」，具体失败原因放在
 * {@link TelemetryEvent#getErrorCategory()} 与 {@link TelemetryEvent#getErrorCode()} 中，
 * 便于控制台按状态着色、指标按状态聚合。
 */
public enum TelemetryStatusEnum {

    /** 阶段正常结束。 */
    OK,

    /** 阶段失败；错误分类与错误码见事件字段。 */
    ERROR,

    /** 阶段被取消（用户中断、上游取消信号等）。 */
    CANCELLED
}
