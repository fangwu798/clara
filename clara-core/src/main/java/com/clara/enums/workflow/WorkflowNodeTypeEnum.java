package com.clara.enums.workflow;

/**
 * 工作流节点类型。
 */
public enum WorkflowNodeTypeEnum {
    /** 智能体节点（执行 agent 能力）。 */
    AGENT,
    /** 工具节点（执行工具调用）。 */
    TOOL,
    /** 顺序组合节点。 */
    SEQUENCE,
    /** 并行组合节点。 */
    PARALLEL,
    /** 条件分支节点。 */
    CONDITION,
    /** 循环节点。 */
    LOOP,
    /** 人工输入节点。 */
    HUMAN,
    /** 多角色评审 / 讨论节点（动作型：多角色轮流发言并由主持人汇总）。 */
    REVIEW
}
