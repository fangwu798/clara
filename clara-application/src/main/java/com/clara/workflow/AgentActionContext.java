package com.clara.workflow;

import com.clara.config.ModelRegistry;
import com.clara.core.skill.IPromptProvider;
import com.clara.orchestration.DefaultConversationOrchestrator;

/**
 * AGENT 动作协作依赖：把创建 {@link AgentWorkflowAction} 所需的四个协作者聚为一个值对象。
 *
 * <p>是什么、在哪一层：application 工作流装配层（包 {@code com.clara.workflow.application}）的依赖载体，
 * 由 {@link AgentActionFactory} 持有并传给每个动作；聚合的目的是避免动作构造参数过多、并让工厂与动作共享同一组协作者。
 */
public final class AgentActionContext {

    private final DefaultConversationOrchestrator orchestrator;
    private final IPromptProvider promptProvider;
    private final ModelRegistry modelRegistry;

    /**
     * 创建协作依赖。
     *
     * @param orchestrator   对话编排器，不可为 null
     * @param promptProvider 提示词提供器，不可为 null
     * @param modelRegistry  模型绑定注册表，不可为 null
     * @throws IllegalArgumentException 任一项为 null 时
     */
    public AgentActionContext(DefaultConversationOrchestrator orchestrator, IPromptProvider promptProvider,
            ModelRegistry modelRegistry) {
        if (orchestrator == null || promptProvider == null || modelRegistry == null) {
            throw new IllegalArgumentException("agent action context dependencies must not be null");
        }
        this.orchestrator = orchestrator;
        this.promptProvider = promptProvider;
        this.modelRegistry = modelRegistry;
    }

    public DefaultConversationOrchestrator orchestrator() {
        return orchestrator;
    }

    public IPromptProvider promptProvider() {
        return promptProvider;
    }

    public ModelRegistry modelRegistry() {
        return modelRegistry;
    }

}
