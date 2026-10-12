package com.clara.core.workflow;

import com.clara.dto.workflow.WorkflowActionSpec;

/**
 * 工作流动作工厂：把配置化的动作描述（节点 id、类型与属性）转换为可执行的工作流动作。
 *
 * <p>是什么、在哪一层：core 定义的工作流契约，供工作流定义加载器在解析 AGENT / TOOL 节点时按类型查表使用，
 * 使「节点类型 → 动作实现」的绑定统一且可扩展，加载器不写死具体动作。
 *
 * <p>谁创建谁调用：由装配层（application）实现并注入加载器；加载器遍历工厂取首个
 * {@link #supports(String)} 命中的工厂创建动作。
 */
public interface IWorkflowActionFactory {

    /**
     * 是否支持给定的动作类型。
     *
     * @param actionKind 动作类型（约定为工作流节点类型名，如 {@code AGENT} / {@code TOOL}），可为 null
     * @return 支持时返回 {@code true}
     */
    boolean supports(String actionKind);

    /**
     * 按动作描述创建动作。
     *
     * @param spec 动作描述，不可为 null
     * @return 工作流动作，非 null
     */
    IWorkflowAction create(WorkflowActionSpec spec);
}
