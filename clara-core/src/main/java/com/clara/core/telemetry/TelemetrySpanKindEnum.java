package com.clara.core.telemetry;

/**
 * Span 的语义类别，用于控制台分组与后续指标维度。
 *
 * <p>取值保持低基数、稳定：不要把具体工具名或模型名做成枚举，它们应放在 Span 属性里。
 */
public enum TelemetrySpanKindEnum {

    /** 一次完整的用户请求（可能包含多次模型尝试）。 */
    TURN,

    /** 一次对话编排尝试。 */
    CONVERSATION,

    /** 意图识别与执行计划。 */
    INTENT,

    /** 上下文装配。 */
    CONTEXT,

    /** 模型调用。 */
    MODEL,

    /** 工具调用（本地技能或 MCP）。 */
    TOOL,

    /** 工作流节点。 */
    WORKFLOW,

    /** 其他未归类的阶段。 */
    OTHER
}
