package com.clara.workflow.application;

import com.clara.core.skill.IPromptProvider;
import com.clara.media.runtime.MediaRuntime;
import com.clara.orchestration.DefaultConversationOrchestrator;
import com.clara.session.ModelRegistry;

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
    private final MediaRuntime mediaRuntime;

    /**
     * 创建协作依赖。
     *
     * @param orchestrator   对话编排器，不可为 null
     * @param promptProvider 提示词提供器，不可为 null
     * @param modelRegistry  模型绑定注册表，不可为 null
     * @param mediaRuntime   媒体运行时（用于图片产物资产化），不可为 null
     * @throws IllegalArgumentException 任一项为 null 时
     */
    public AgentActionContext(DefaultConversationOrchestrator orchestrator, IPromptProvider promptProvider,
            ModelRegistry modelRegistry, MediaRuntime mediaRuntime) {
        if (orchestrator == null || promptProvider == null || modelRegistry == null || mediaRuntime == null) {
            throw new IllegalArgumentException("agent action context dependencies must not be null");
        }
        this.orchestrator = orchestrator;
        this.promptProvider = promptProvider;
        this.modelRegistry = modelRegistry;
        this.mediaRuntime = mediaRuntime;
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

    public MediaRuntime mediaRuntime() {
        return mediaRuntime;
    }
}
