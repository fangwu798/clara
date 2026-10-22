package com.clara.workflow.engine;

import com.clara.core.telemetry.TelemetryContext;
import com.clara.core.workflow.ICheckpointStore;
import com.clara.dto.workflow.ExecutionScope;
import com.clara.dto.workflow.WorkflowCheckpoint;
import com.clara.dto.workflow.WorkflowDefinition;
import com.clara.dto.workflow.WorkflowResult;
import com.clara.enums.workflow.WorkflowInstanceStatusEnum;

/**
 * 单次工作流执行的状态：定义、实例标识、变量作用域与检查点版本。
 *
 * <p>由引擎创建，分支执行时以 {@link #branch(ExecutionScope)} 复制出独立的运行状态，
 * 检查点通过构造时注入的存储持久化。</p>
 */
final class WorkflowRun {

    /** 检查点存储。 */
    private final ICheckpointStore checkpoints;

    /** 本次执行的工作流定义；取消或挂起场景下可为 null。 */
    final WorkflowDefinition definition;

    /** 工作流实例标识。 */
    final String instanceId;

    /** 执行期变量作用域。 */
    final ExecutionScope scope;

    /** 工作流标识，恢复执行时取自检查点。 */
    final String workflowId;

    /** 工作流版本，恢复执行时取自检查点。 */
    final String workflowVersion;

    /** 当前检查点版本，每次持久化后递增。 */
    long checkpointVersion;

    /** 工作流执行 Span 的链路上下文；并行分支据此把节点挂到同一条 trace。 */
    TelemetryContext telemetryContext;

    /**
     * 以工作流定义中的标识与版本创建运行状态。
     *
     * @param checkpoints 检查点存储
     * @param definition 工作流定义
     * @param instanceId 实例标识
     * @param scope 变量作用域
     * @param version 检查点版本
     */
    WorkflowRun(ICheckpointStore checkpoints, WorkflowDefinition definition, String instanceId,
            ExecutionScope scope, long version) {
        this(checkpoints, definition, instanceId, scope, version,
                definition.getWorkflowId(), definition.getVersion());
    }

    /**
     * 以显式的标识与版本创建运行状态，用于从检查点恢复或取消实例。
     *
     * @param checkpoints 检查点存储
     * @param definition 工作流定义，可为 null
     * @param instanceId 实例标识
     * @param scope 变量作用域
     * @param version 检查点版本
     * @param workflowId 工作流标识
     * @param workflowVersion 工作流版本
     */
    WorkflowRun(ICheckpointStore checkpoints, WorkflowDefinition definition, String instanceId,
            ExecutionScope scope, long version, String workflowId, String workflowVersion) {
        this.checkpoints = checkpoints;
        this.definition = definition;
        this.instanceId = instanceId;
        this.scope = scope;
        this.checkpointVersion = version;
        this.workflowId = workflowId;
        this.workflowVersion = workflowVersion;
    }

    /**
     * 以当前作用域写入一份检查点并递增版本号。
     *
     * @param status 实例状态
     */
    void checkpoint(WorkflowInstanceStatusEnum status) {
        WorkflowCheckpoint value = new WorkflowCheckpoint(instanceId, workflowId, workflowVersion,
                status, scope, checkpointVersion + 1L);
        checkpoints.save(value, checkpointVersion);
        checkpointVersion++;
    }

    /**
     * 基于给定作用域派生并行分支的运行状态。
     *
     * @param branchScope 分支独立的作用域
     * @return 新的运行状态
     */
    WorkflowRun branch(ExecutionScope branchScope) {
        WorkflowRun branch = new WorkflowRun(checkpoints, definition, instanceId, branchScope, checkpointVersion,
                workflowId, workflowVersion);
        branch.telemetryContext = telemetryContext;
        return branch;
    }

    /**
     * 构造执行结果。
     *
     * @param status 实例状态
     * @return 工作流结果
     */
    WorkflowResult result(WorkflowInstanceStatusEnum status) {
        return new WorkflowResult(instanceId, status, scope, checkpointVersion);
    }

    /**
     * 构造等待人工输入的执行结果。
     *
     * @param status 实例状态
     * @param waitingNodeId 等待输入的节点标识
     * @param waitingInputKey 等待的输入键
     * @return 工作流结果
     */
    WorkflowResult result(WorkflowInstanceStatusEnum status, String waitingNodeId, String waitingInputKey) {
        return new WorkflowResult(instanceId, status, scope, checkpointVersion, waitingNodeId, waitingInputKey);
    }
}
