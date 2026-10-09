package com.clara.core.interaction;

import com.clara.dto.workflow.WorkflowDefinition;

/**
 * 工作流定义解析接口：按实例标识解析工作流定义。
 *
 * <p>core 定义的契约接口，由宿主或模块实现；恢复交互时加载定义。
 */
public interface IWorkflowDefinitionResolver {

    /**
     * 按实例标识解析工作流定义。
     *
     * @param instanceId 实例标识，不可为 null
     * @return 工作流定义，非 null
     */
    WorkflowDefinition resolve(String instanceId);
}
