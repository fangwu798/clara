package com.clara.workflow;

import com.clara.config.ModelRegistry;
import com.clara.core.skill.IPromptProvider;
import com.clara.core.workflow.IWorkflowAction;
import com.clara.core.workflow.IWorkflowActionFactory;
import com.clara.dto.workflow.WorkflowActionSpec;
import com.clara.orchestration.DefaultConversationOrchestrator;
import com.clara.workflow.runtime.ReviewNodeOptions;

/**
 * REVIEW 动作工厂：把配置化的 REVIEW 节点描述转换为 {@link ReviewWorkflowAction}。
 *
 * <p>是什么、在哪一层：application 工作流装配层对 core {@link IWorkflowActionFactory} 的实现
 * （包 {@code com.clara.workflow.application}），由装配层创建并注入工作流定义加载器。
 *
 * <p>属性约定：必需 {@code prompt}、{@code roles}（角色数组）、{@code moderator}；可选 {@code topic}、
 * {@code rounds}。失败语义：必填项缺失或类型非法时抛 {@link IllegalArgumentException}。
 */
public final class ReviewActionFactory implements IWorkflowActionFactory {

    private final DefaultConversationOrchestrator orchestrator;
    private final IPromptProvider promptProvider;
    private final ModelRegistry modelRegistry;

    /**
     * 创建 REVIEW 动作工厂。
     *
     * @param orchestrator   对话编排器，不可为 null
     * @param promptProvider 提示词提供器，不可为 null
     * @param modelRegistry  模型绑定注册表，不可为 null
     * @throws IllegalArgumentException 任一项为 null 时
     */
    public ReviewActionFactory(DefaultConversationOrchestrator orchestrator, IPromptProvider promptProvider,
            ModelRegistry modelRegistry) {
        if (orchestrator == null || promptProvider == null || modelRegistry == null) {
            throw new IllegalArgumentException("review factory dependencies must not be null");
        }
        this.orchestrator = orchestrator;
        this.promptProvider = promptProvider;
        this.modelRegistry = modelRegistry;
    }

    @Override
    public boolean supports(String actionKind) {
        return "REVIEW".equalsIgnoreCase(actionKind);
    }

    @Override
    public IWorkflowAction create(WorkflowActionSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spec must not be null");
        }
        return new ReviewWorkflowAction(orchestrator, promptProvider, modelRegistry, spec.getNodeId(),
                ReviewNodeOptions.fromSpec(spec));
    }
}
