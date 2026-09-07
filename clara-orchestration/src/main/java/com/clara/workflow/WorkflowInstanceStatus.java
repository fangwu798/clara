package com.clara.workflow;

/**
 * 定义工作流实例的状态取值。
 */
public enum WorkflowInstanceStatus {
    CREATED, RUNNING, WAITING_INPUT, SUSPENDED, SUCCEEDED, FAILED, CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
