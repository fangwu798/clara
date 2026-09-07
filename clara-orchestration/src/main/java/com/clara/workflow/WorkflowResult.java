package com.clara.workflow;

/**
 * 封装工作流操作的执行结果。
 */
public final class WorkflowResult {
    private final String instanceId;
    private final WorkflowInstanceStatus status;
    private final ExecutionScope scope;
    private final long checkpointVersion;
    private final String waitingNodeId;
    private final String waitingInputKey;

    public WorkflowResult(String instanceId, WorkflowInstanceStatus status,
            ExecutionScope scope, long checkpointVersion) {
        this(instanceId, status, scope, checkpointVersion, null, null);
    }
    public WorkflowResult(String instanceId, WorkflowInstanceStatus status,
            ExecutionScope scope, long checkpointVersion, String waitingNodeId,
            String waitingInputKey) {
        this.instanceId = instanceId; this.status = status;
        this.scope = scope.copy(); this.checkpointVersion = checkpointVersion;
        this.waitingNodeId = waitingNodeId; this.waitingInputKey = waitingInputKey;
    }
    public String getInstanceId() { return instanceId; }
    public WorkflowInstanceStatus getStatus() { return status; }
    public ExecutionScope getScope() { return scope.copy(); }
    public long getCheckpointVersion() { return checkpointVersion; }
    public String getWaitingNodeId() { return waitingNodeId; }
    public String getWaitingInputKey() { return waitingInputKey; }
}
