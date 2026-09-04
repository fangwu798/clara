package com.clara.workflow;

import java.util.Map;

public interface WorkflowAction {
    Map<String, Object> execute(ExecutionScope scope);
}
