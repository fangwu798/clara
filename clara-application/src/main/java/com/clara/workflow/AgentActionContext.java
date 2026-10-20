package com.clara.workflow;

import com.clara.config.ModelRegistry;
import com.clara.core.skill.IPromptProvider;
import com.clara.media.VideoDownloader;
import com.clara.orchestration.DefaultConversationOrchestrator;
import com.clara.usage.CapabilityUsageLedger;

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
    private final VideoDownloader videoDownloader;
    private final CapabilityUsageLedger usageLedger;

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
        this(orchestrator, promptProvider, modelRegistry, new VideoDownloader(), new CapabilityUsageLedger());
    }

    /**
     * 创建协作依赖，并指定视频产物下载器。
     *
     * @param orchestrator    对话编排器，不可为 null
     * @param promptProvider  提示词提供器，不可为 null
     * @param modelRegistry   模型绑定注册表，不可为 null
     * @param videoDownloader 视频产物下载器，不可为 null
     * @throws IllegalArgumentException 任一项为 null 时
     */
    public AgentActionContext(DefaultConversationOrchestrator orchestrator, IPromptProvider promptProvider,
            ModelRegistry modelRegistry, VideoDownloader videoDownloader) {
        this(orchestrator, promptProvider, modelRegistry, videoDownloader, new CapabilityUsageLedger());
    }

    /**
     * 创建协作依赖，并共享宿主的用量台账。
     *
     * @param orchestrator    对话编排器，不可为 null
     * @param promptProvider  提示词提供器，不可为 null
     * @param modelRegistry   模型绑定注册表，不可为 null
     * @param videoDownloader 视频产物下载器，不可为 null
     * @param usageLedger     能力用量台账，不可为 null
     * @throws IllegalArgumentException 任一项为 null 时
     */
    public AgentActionContext(DefaultConversationOrchestrator orchestrator, IPromptProvider promptProvider,
            ModelRegistry modelRegistry, VideoDownloader videoDownloader, CapabilityUsageLedger usageLedger) {
        if (orchestrator == null || promptProvider == null || modelRegistry == null || videoDownloader == null
                || usageLedger == null) {
            throw new IllegalArgumentException("agent action context dependencies must not be null");
        }
        this.orchestrator = orchestrator;
        this.promptProvider = promptProvider;
        this.modelRegistry = modelRegistry;
        this.videoDownloader = videoDownloader;
        this.usageLedger = usageLedger;
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

    public VideoDownloader videoDownloader() {
        return videoDownloader;
    }

    /**
     * 能力用量台账（图片 / 视频 / 图谱分开累计）。
     *
     * @return 非 null
     */
    public CapabilityUsageLedger usageLedger() {
        return usageLedger;
    }

}
