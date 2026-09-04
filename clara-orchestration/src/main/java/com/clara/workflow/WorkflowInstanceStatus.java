package com.clara.workflow;

public enum WorkflowInstanceStatus {
    CREATED, RUNNING, WAITING_INPUT, SUSPENDED, SUCCEEDED, FAILED, CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
