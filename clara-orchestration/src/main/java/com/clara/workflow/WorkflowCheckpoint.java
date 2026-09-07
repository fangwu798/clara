package com.clara.workflow;

/**
 * 表示。
 */
public final class WorkflowCheckpoint {
    private static final int SCHEMA_VERSION = 1;
    private final String instanceId;
    private final String workflowId;
    private final String workflowVersion;
    private final WorkflowInstanceStatus status;
    private final ExecutionScope scope;
    private final long version;

    public WorkflowCheckpoint(String instanceId, String workflowId, String workflowVersion,
            WorkflowInstanceStatus status, ExecutionScope scope, long version) {
        if (blank(instanceId) || blank(workflowId) || blank(workflowVersion)
                || status == null || scope == null || version < 0L) {
            throw new IllegalArgumentException("checkpoint fields are invalid");
        }
        this.instanceId = instanceId; this.workflowId = workflowId;
        this.workflowVersion = workflowVersion; this.status = status;
        this.scope = scope.copy(); this.version = version;
    }
    public int getSchemaVersion() { return SCHEMA_VERSION; }
    public String getInstanceId() { return instanceId; }
    public String getWorkflowId() { return workflowId; }
    public String getWorkflowVersion() { return workflowVersion; }
    public WorkflowInstanceStatus getStatus() { return status; }
    public ExecutionScope getScope() { return scope.copy(); }
    public long getVersion() { return version; }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
