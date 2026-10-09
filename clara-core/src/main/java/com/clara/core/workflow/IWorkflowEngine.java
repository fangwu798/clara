package com.clara.core.workflow;

import com.clara.dto.workflow.WorkflowDefinition;
import com.clara.dto.workflow.WorkflowResult;

import java.io.Closeable;
import java.util.Map;

/**
 * 工作流引擎接口：执行确定性工作流，并通过检查点支持暂停、人工输入、恢复与取消。
 *
 * <p>core 只定义契约，由 clara-workflow 提供实现；使用完毕后应调用 {@link #close()} 释放资源。
 */
public interface IWorkflowEngine extends Closeable {

    /**
     * 首次启动一个工作流实例。
     */
    WorkflowResult start(WorkflowDefinition definition, String instanceId, Map<String, Object> inputs);

    /**
     * 恢复一个处于等待或暂停状态的实例（不校验检查点版本）。
     */
    WorkflowResult resume(WorkflowDefinition definition, String instanceId, Map<String, Object> inputs);

    /**
     * 恢复实例并校验期望的检查点版本，防止并发恢复冲突。
     */
    WorkflowResult resume(WorkflowDefinition definition, String instanceId, Map<String, Object> inputs,
            long expectedCheckpointVersion);

    /**
     * 取消实例。
     */
    WorkflowResult cancel(String instanceId);

    /**
     * 暂停实例。
     */
    WorkflowResult suspend(String instanceId);
}
