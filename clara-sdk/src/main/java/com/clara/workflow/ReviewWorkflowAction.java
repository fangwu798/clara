package com.clara.workflow;

import com.clara.utils.StringUtils;
import com.clara.config.ModelRegistry;
import com.clara.core.skill.IPromptProvider;
import com.clara.core.workflow.IWorkflowAction;
import com.clara.dto.workflow.ExecutionScope;
import com.clara.enums.CapabilityKindEnum;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.orchestration.DefaultConversationOrchestrator;
import com.clara.orchestration.api.ConversationRequest;
import com.clara.orchestration.api.ConversationResult;
import com.clara.orchestration.api.OrchestrationStatusEnum;
import com.clara.workflow.runtime.WorkflowProgress;
import com.clara.workflow.runtime.ReviewNodeOptions;
import com.clara.workflow.runtime.WorkflowVariableResolver;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REVIEW 工作流动作：让多个角色围绕同一主题多轮发言，最后由主持人汇总为结论。
 *
 * <p>是什么、在哪一层：application 工作流装配层对 core {@link IWorkflowAction} 的实现
 * （包 {@code com.clara.workflow.application}），由 {@link ReviewActionFactory} 创建，供工作流引擎在
 * REVIEW 节点执行；是「多角色审核 / 讨论」的落地载体。
 *
 * <p>执行流程：逐轮、逐个角色基于「已累积的讨论稿」发言并追加 → 结束后由主持人（{@code moderator}）
 * 汇总结论作为输出。每个角色 / 主持人经 {@link ModelRegistry} 按角色名选到各自的平台与模型（可同模型、
 * 也可异模型），均以 {@link CapabilityKindEnum#CHAT} 调用。
 *
 * <p>可见性：讨论稿是内部累积状态，用户看不到；因此每位角色的发言与主持人的最终决议都会经
 * {@link WorkflowProgress#statement(String, String)} 输出到当轮控制台（宿主按角色着色，未接入控制台时
 * 降级为诊断输出），同时 {@link WorkflowProgress#speaking(String, String)} 让宿主把「当前角色 · 模型」
 * 实时显示在底部状态行，让用户能看出这是多个模型在交流，而不是一个模型自演。
 *
 * <p>提示词变量：{@code topic}（主题）、{@code role}（发言人角色）、{@code round}（轮次）、
 * {@code discussion}（此前累积的讨论稿）、{@code isFinal}（主持人汇总轮为 {@code true}）。
 *
 * <p>失败语义：某位发言人调用未成功或回复为空时抛 {@link IllegalStateException}（含角色与轮次），
 * 由引擎把节点标记为失败。
 */
public final class ReviewWorkflowAction implements IWorkflowAction {

    private final DefaultConversationOrchestrator orchestrator;
    private final IPromptProvider promptProvider;
    private final ModelRegistry modelRegistry;
    private final String nodeId;
    private final ReviewNodeOptions options;

    /**
     * 创建 REVIEW 动作。
     *
     * @param orchestrator   对话编排器，不可为 null
     * @param promptProvider 提示词提供器，不可为 null
     * @param modelRegistry  模型绑定注册表，不可为 null
     * @param nodeId         节点 id，不可为空白
     * @param options        节点选项，不可为 null
     * @throws IllegalArgumentException 依赖或必填项非法时
     */
    public ReviewWorkflowAction(DefaultConversationOrchestrator orchestrator, IPromptProvider promptProvider,
            ModelRegistry modelRegistry, String nodeId, ReviewNodeOptions options) {
        if (orchestrator == null || promptProvider == null || modelRegistry == null || options == null) {
            throw new IllegalArgumentException("review action dependencies must not be null");
        }
        if (StringUtils.isBlank(nodeId)) {
            throw new IllegalArgumentException("nodeId is required");
        }
        this.orchestrator = orchestrator;
        this.promptProvider = promptProvider;
        this.modelRegistry = modelRegistry;
        this.nodeId = nodeId;
        this.options = options;
    }

    @Override
    public Map<String, Object> execute(ExecutionScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        Object instanceValue = scope.get("instanceId");
        String instanceId = instanceValue == null ? nodeId : String.valueOf(instanceValue);
        String topic = resolveTopic(scope);

        try {
            StringBuilder discussion = new StringBuilder();
            for (int round = 1; round <= options.rounds(); round++) {
                for (String role : options.roles()) {
                    WorkflowProgress.notice("[workflow] 多角色讨论 第 " + round + "/" + options.rounds()
                            + " 轮：" + role + " 发言中…");
                    String statement = speak(instanceId, role, round, topic, discussion.toString(), false);
                    discussion.append("\n### ").append(role).append("（第 ").append(round).append(" 轮）\n")
                            .append(statement);
                    // 讨论过程必须对用户可见：否则控制台只有「发言中…」，看不到任何角色观点。
                    report(role, role + "（第 " + round + "/" + options.rounds() + " 轮）", statement);
                }
            }
            WorkflowProgress.notice("[workflow] 讨论结束：" + options.moderator() + " 汇总决议中…");
            String conclusion = speak(instanceId, options.moderator(), options.rounds(), topic,
                    discussion.toString(), true);
            report(options.moderator(), "汇总决议 · " + options.moderator(), conclusion);
            WorkflowProgress.notice("[workflow] 多角色讨论完成（" + options.roles().size() + " 个角色 × "
                    + options.rounds() + " 轮 + 主持人汇总）");

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("output", conclusion);
            return response;
        } finally {
            // 无论成功、失败还是取消，都要把底部状态行从「多角色角色名」恢复为会话当前模型。
            WorkflowProgress.speaking(null, null);
        }
    }

    /**
     * 把一段多行内容连同标题输出到当轮控制台。
     *
     * <p>一次调用输出整段（含换行）而不是逐行调用：减少控制台与详细日志的逐行刷写，也让同一角色的发言在
     * 日志里保持连续、不被其它线程的输出打断。
     *
     * @param speaker 发言人（角色名），用于宿主着色
     * @param header  标题，如「architect（第 1/2 轮）」
     * @param content 发言或决议正文，不可为 null
     */
    private static void report(String speaker, String header, String content) {
        WorkflowProgress.statement(speaker, header + "：\n" + content);
    }

    private String speak(String instanceId, String role, int round, String topic, String discussion,
            boolean isFinal) {
        ProviderConfiguration configuration = modelRegistry.resolve(role, null);
        configuration.getCapability(CapabilityKindEnum.CHAT);
        String model = configuration.toConfig(CapabilityKindEnum.CHAT).getModelId();
        // 底部状态行实时展示「当前角色 · 模型」：用户据此看出这是不同模型在交流。
        WorkflowProgress.speaking(role, model);

        ConversationResult result = orchestrator.converse(ConversationRequest.builder()
                .conversationId("wf:" + instanceId + ":review:" + nodeId + ":" + role + ":r" + round)
                .provider(configuration.getProvider())
                .model(model)
                .capabilityKind(CapabilityKindEnum.CHAT)
                .userInput(userInput(role, round, topic, discussion, isFinal))
                .planningEnabled(false)
                .providerConfiguration(configuration)
                .stream(false)
                .build());

        if (result.getStatus() != OrchestrationStatusEnum.COMPLETED) {
            throw new IllegalStateException("REVIEW 节点发言人调用未成功（节点 " + nodeId + "，角色 " + role
                    + "，第 " + round + " 轮，状态 " + result.getStatus() + (result.getError() == null
                    ? "" : "，错误 " + result.getError().getSafeMessage()) + "）");
        }
        String content = result.getContent() == null ? "" : result.getContent().trim();
        if (content.isEmpty()) {
            throw new IllegalStateException("REVIEW 节点发言人回复为空（节点 " + nodeId + "，角色 " + role
                    + "，第 " + round + " 轮）");
        }
        return content;
    }

    private String resolveTopic(ExecutionScope scope) {
        if (options.topic() == null) {
            return "";
        }
        return WorkflowVariableResolver.stringify(
                WorkflowVariableResolver.resolveValue(options.topic(), scope, nodeId));
    }

    private String userInput(String role, int round, String topic, String discussion, boolean isFinal) {
        StringBuilder builder = new StringBuilder();
        builder.append(promptProvider.prompt(options.promptId()));
        appendVar(builder, "topic", topic);
        appendVar(builder, "role", role);
        appendVar(builder, "round", String.valueOf(round));
        appendVar(builder, "discussion", discussion.isEmpty() ? "（暂无）" : discussion);
        appendVar(builder, "isFinal", String.valueOf(isFinal));
        return builder.toString();
    }

    private static void appendVar(StringBuilder builder, String name, String value) {
        builder.append('\n').append("## ").append(name).append('\n').append(value);
    }

}
