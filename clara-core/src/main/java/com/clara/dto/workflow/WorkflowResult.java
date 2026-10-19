package com.clara.dto.workflow;

import com.clara.enums.workflow.WorkflowInstanceStatusEnum;
import lombok.Data;

import java.util.Objects;

/**
 * 工作流执行结果：封装一次执行（启动 / 恢复 / 取消 / 暂停）后的状态快照。
 */
@Data
public final class WorkflowResult {

    private final String instanceId;
    private final WorkflowInstanceStatusEnum status;
    private final ExecutionScope scope;
    private final long checkpointVersion;
    private final String waitingNodeId;
    private final String waitingInputKey;

    /**
     * 创建非等待态的执行结果。
     */
    public WorkflowResult(String instanceId, WorkflowInstanceStatusEnum status, ExecutionScope scope, long checkpointVersion) {
        this(instanceId, status, scope, checkpointVersion, null, null);
    }

    /**
     * 创建执行结果（可携带等待输入信息）。
     */
    public WorkflowResult(String instanceId, WorkflowInstanceStatusEnum status, ExecutionScope scope, long checkpointVersion, String waitingNodeId, String waitingInputKey) {
        this.instanceId = instanceId;
        this.status = status;
        this.scope = scope.copy();
        this.checkpointVersion = checkpointVersion;
        this.waitingNodeId = waitingNodeId;
        this.waitingInputKey = waitingInputKey;
    }

    public ExecutionScope getScope() {
        return scope.copy();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WorkflowResult)) {
            return false;
        }
        WorkflowResult that = (WorkflowResult) other;
        return checkpointVersion == that.checkpointVersion
                && status == that.status
                && Objects.equals(instanceId, that.instanceId)
                && Objects.equals(scope, that.scope)
                && Objects.equals(waitingNodeId, that.waitingNodeId)
                && Objects.equals(waitingInputKey, that.waitingInputKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(instanceId, status, scope, checkpointVersion, waitingNodeId, waitingInputKey);
    }

}
