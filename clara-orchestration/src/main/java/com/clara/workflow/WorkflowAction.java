package com.clara.workflow;

import java.util.Map;

/**
 * 定义。
 */
public interface WorkflowAction {
    Map<String, Object> execute(ExecutionScope scope);
}
