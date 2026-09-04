package com.clara.workflow;

import java.util.Set;

public interface WorkflowNode {
    String getId();
    WorkflowNodeType getType();
    Set<String> getOutputKeys();
}
