package com.clara.orchestration.workflow;

import com.clara.core.interaction.IWorkflowDefinitionResolver;
import com.clara.core.workflow.IWorkflowEngine;
import com.clara.dto.workflow.WorkflowDefinition;
import com.clara.dto.workflow.WorkflowResult;

import java.util.Map;

/**
 * 工作流步骤调用器：把某个工作流作为编排中的一个步骤调用，用输入启动实例并取回结果。
 *
 * <p>工作流定义由宿主 / 上层提供（{@link IWorkflowDefinitionResolver} 解析），编排层只负责作为一步调用并
 * 消费 {@link WorkflowResult}；执行走 {@link IWorkflowEngine}。
 */
public final class WorkflowStepInvoker {

    private final IWorkflowEngine workflowEngine;
    private final IWorkflowDefinitionResolver definitionResolver;

    /**
     * 创建工作流步骤调用器。
     *
     * @param workflowEngine    工作流引擎，不可为 null
     * @param definitionResolver 工作流定义解析器，不可为 null
     */
    public WorkflowStepInvoker(IWorkflowEngine workflowEngine,
            IWorkflowDefinitionResolver definitionResolver) {
        if (workflowEngine == null || definitionResolver == null) {
            throw new IllegalArgumentException("workflow step invoker dependencies are invalid");
        }
        this.workflowEngine = workflowEngine;
        this.definitionResolver = definitionResolver;
    }

    /**
     * 启动一次工作流实例。
     *
     * @param instanceId 实例标识，不可为 null
     * @param inputs     启动输入，可为 null
     * @return 执行结果，非 null
     */
    public WorkflowResult invoke(String instanceId, Map<String, Object> inputs) {
        WorkflowDefinition definition = definitionResolver.resolve(instanceId);
        return workflowEngine.start(definition, instanceId, inputs);
    }

    /**
     * 恢复一次工作流实例并校验期望的检查点版本。
     *
     * @param instanceId              实例标识，不可为 null
     * @param inputs                  恢复输入，可为 null
     * @param expectedCheckpointVersion 期望的检查点版本
     * @return 执行结果，非 null
     */
    public WorkflowResult resume(String instanceId, Map<String, Object> inputs,
            long expectedCheckpointVersion) {
        WorkflowDefinition definition = definitionResolver.resolve(instanceId);
        return workflowEngine.resume(definition, instanceId, inputs, expectedCheckpointVersion);
    }
}
