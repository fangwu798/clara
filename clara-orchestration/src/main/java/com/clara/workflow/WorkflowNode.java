package com.clara.workflow;

import java.util.Set;

/**
 * 定义。
 */
public interface WorkflowNode {
    String getId();
    WorkflowNodeType getType();
    Set<String> getOutputKeys();
}
