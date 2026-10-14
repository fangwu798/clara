package com.clara.workflow.application;

import com.clara.core.skill.IPromptProvider;
import com.clara.core.workflow.IWorkflowAction;
import com.clara.dto.workflow.ExecutionScope;
import com.clara.enums.CapabilityKindEnum;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.orchestration.DefaultConversationOrchestrator;
import com.clara.orchestration.api.ConversationRequest;
import com.clara.orchestration.api.ConversationResult;
import com.clara.orchestration.api.OrchestrationStatusEnum;
import com.clara.session.ModelBinding;
import com.clara.session.ModelRegistry;
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
        if (blank(nodeId)) {
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

        StringBuilder discussion = new StringBuilder();
        for (int round = 1; round <= options.rounds(); round++) {
            for (String role : options.roles()) {
                String statement = speak(instanceId, role, round, topic, discussion.toString(), false);
                discussion.append("\n### ").append(role).append("（第 ").append(round).append(" 轮）\n")
                        .append(statement);
            }
        }
        String conclusion = speak(instanceId, options.moderator(), options.rounds(), topic,
                discussion.toString(), true);

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("output", conclusion);
        return response;
    }

    private String speak(String instanceId, String role, int round, String topic, String discussion,
            boolean isFinal) {
        ModelBinding binding = modelRegistry.resolve(role, null);
        ProviderConfiguration configuration = binding.configuration();
        configuration.getCapability(CapabilityKindEnum.CHAT);
        String model = configuration.toConfig(CapabilityKindEnum.CHAT).getModelId();

        ConversationResult result = orchestrator.converse(ConversationRequest.builder()
                .conversationId("wf:" + instanceId + ":review:" + nodeId + ":" + role + ":r" + round)
                .provider(configuration.getProvider())
                .model(model)
                .capabilityKind(CapabilityKindEnum.CHAT)
                .userInput(userInput(role, round, topic, discussion, isFinal))
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

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
