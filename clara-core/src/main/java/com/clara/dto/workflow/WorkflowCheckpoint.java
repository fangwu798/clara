package com.clara.dto.workflow;

import com.clara.utils.StringUtils;
import com.clara.enums.workflow.WorkflowInstanceStatusEnum;
import lombok.Data;

/**
 * 工作流检查点：工作流实例的可恢复状态快照，含实例标识、工作流标识与版本、状态、作用域与版本号。
 */
@Data
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
     * @throws IllegalArgumentException 任意字段不合法时
     */
    public WorkflowCheckpoint(String instanceId, String workflowId, String workflowVersion, WorkflowInstanceStatusEnum status, ExecutionScope scope, long version) {
        if (StringUtils.isBlank(instanceId) || StringUtils.isBlank(workflowId) || StringUtils.isBlank(workflowVersion) || status == null || scope == null || version < 0L) {
            throw new IllegalArgumentException("checkpoint fields are invalid");
        }
        this.instanceId = instanceId;
        this.workflowId = workflowId;
        this.workflowVersion = workflowVersion;
        this.status = status;
        this.scope = scope.copy();
        this.version = version;
    }

    public ExecutionScope getScope() {
        return scope.copy();
    }
}
