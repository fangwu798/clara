package com.clara.dto.workflow;

import com.clara.enums.workflow.WorkflowInstanceStatusEnum;

/**
 * 工作流检查点：工作流实例的可恢复状态快照，含实例标识、工作流标识与版本、状态、作用域与版本号。
 */
public final class WorkflowCheckpoint {

    private static final int SCHEMA_VERSION = 1;

    private final String instanceId;
    private final String workflowId;
    private final String workflowVersion;
    private final WorkflowInstanceStatusEnum status;
    private final ExecutionScope scope;
    private final long version;

    /**
     * 创建检查点。
     *
     * @param instanceId      实例标识，不可为空白
     * @param workflowId      工作流标识，不可为空白
     * @param workflowVersion 工作流版本，不可为空白
     * @param status          实例状态，不可为 null
     * @param scope           执行作用域，不可为 null
     * @param version         检查点版本，不可为负
     * @throws IllegalArgumentException 任一字段不合法时
     */
    public WorkflowCheckpoint(String instanceId, String workflowId, String workflowVersion,
            WorkflowInstanceStatusEnum status, ExecutionScope scope, long version) {
        if (blank(instanceId) || blank(workflowId) || blank(workflowVersion)
                || status == null || scope == null || version < 0L) {
            throw new IllegalArgumentException("checkpoint fields are invalid");
        }
        this.instanceId = instanceId;
        this.workflowId = workflowId;
        this.workflowVersion = workflowVersion;
        this.status = status;
        this.scope = scope.copy();
        this.version = version;
    }

    public int getSchemaVersion() {
        return SCHEMA_VERSION;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getWorkflowVersion() {
        return workflowVersion;
    }

    public WorkflowInstanceStatusEnum getStatus() {
        return status;
    }

    public ExecutionScope getScope() {
        return scope.copy();
    }

    public long getVersion() {
        return version;
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
