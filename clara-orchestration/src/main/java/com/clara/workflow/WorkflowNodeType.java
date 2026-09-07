package com.clara.workflow;

/**
 * 定义工作流节点的类型取值。
 */
public enum WorkflowNodeType {
    AGENT, TOOL, SEQUENCE, PARALLEL, CONDITION, LOOP, HUMAN
}
