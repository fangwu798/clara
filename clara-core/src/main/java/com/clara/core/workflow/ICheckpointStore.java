package com.clara.core.workflow;

import com.clara.dto.workflow.WorkflowCheckpoint;

import java.util.Optional;

/**
 * 检查点存储接口：保存并读取工作流实例的可恢复状态快照。
 *
 * <p>core 只定义契约，由 clara-workflow 提供实现；生产环境应替换为持久化、可并发控制的实现。
 */
public interface ICheckpointStore {

    /**
     * 按实例标识读取最新检查点。
     */
    Optional<WorkflowCheckpoint> load(String instanceId);

    /**
     * 按实例标识与版本读取指定检查点。
     */
    Optional<WorkflowCheckpoint> load(String instanceId, long version);

    /**
     * 保存检查点。
     */
    WorkflowCheckpoint save(WorkflowCheckpoint checkpoint, long expectedVersion);
}
