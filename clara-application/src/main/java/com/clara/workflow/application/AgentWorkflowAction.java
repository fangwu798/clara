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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AGENT 工作流动作：用提示词与变量调用对话模型，并把回复文本作为输出。
 *
 * <p>是什么、在哪一层：application 工作流装配层对 core {@link IWorkflowAction} 的实现
 * （包 {@code com.clara.workflow.application}），由 {@link AgentActionFactory} 创建，供工作流引擎在
 * AGENT 节点执行。
 *
 * <p>执行流程：取绑定的模型配置（未绑定则失败）→ 组装提示词与变量为 userInput → 经
 * {@link DefaultConversationOrchestrator} 调用 CHAT 能力 → 回复文本尝试解析为 JSON 对象，解析成功以解析
 * 后的 Map 作为输出值，否则用原始文本；返回 {@code {"output": <值>}} 供节点落位。
 *
 * <p>失败语义：模型配置未绑定抛 {@link IllegalStateException}；模型调用异常直接向上抛，由引擎把节点标记为失败。
 */
public final class AgentWorkflowAction implements IWorkflowAction {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DefaultConversationOrchestrator orchestrator;
    private final IPromptProvider promptProvider;
    private final WorkflowModelContext modelContext;
    private final String nodeId;
    private final String promptId;
    private final Map<String, Object> promptVars;

    /**
     * 创建 AGENT 动作。
     *
     * @param orchestrator   对话编排器，不可为 null
     * @param promptProvider 提示词提供器，不可为 null
     * @param modelContext   工作流模型上下文，不可为 null
     * @param nodeId         节点 id，不可为空白
     * @param promptId       提示词标识，不可为空白
     * @param promptVars     提示词变量模板，可为 null（视为空）
     * @throws IllegalArgumentException 依赖或必填项非法时
     */
    public AgentWorkflowAction(DefaultConversationOrchestrator orchestrator, IPromptProvider promptProvider,
            WorkflowModelContext modelContext, String nodeId, String promptId, Map<String, Object> promptVars) {
        if (orchestrator == null || promptProvider == null || modelContext == null) {
            throw new IllegalArgumentException("agent action dependencies must not be null");
        }
        if (blank(nodeId) || blank(promptId)) {
            throw new IllegalArgumentException("nodeId and promptId are required");
        }
        this.orchestrator = orchestrator;
        this.promptProvider = promptProvider;
        this.modelContext = modelContext;
        this.nodeId = nodeId;
        this.promptId = promptId;
        this.promptVars = promptVars == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(promptVars);
    }

    @Override
    public Map<String, Object> execute(ExecutionScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        ProviderConfiguration configuration = modelContext.configuration();
        if (configuration == null) {
            throw new IllegalStateException("工作流 AGENT 节点尚未绑定模型配置");
        }
        String model = modelContext.model();
        if (blank(model)) {
            model = configuration.toConfig(CapabilityKindEnum.CHAT).getModelId();
        }
        Object instanceValue = scope.get("instanceId");
        String instanceId = instanceValue == null ? nodeId : String.valueOf(instanceValue);
        String conversationId = "wf:" + instanceId + ":" + nodeId;

        ConversationResult result = orchestrator.converse(ConversationRequest.builder()
                .conversationId(conversationId)
                .provider(configuration.getProvider())
                .model(model)
                .capabilityKind(CapabilityKindEnum.CHAT)
                .userInput(userInput(scope))
                .providerConfiguration(configuration)
                .stream(false)
                .build());

        if (result.getStatus() != OrchestrationStatusEnum.COMPLETED) {
            throw new IllegalStateException("工作流 AGENT 节点对话未成功（节点 " + nodeId + "，状态 "
                    + result.getStatus() + (result.getError() == null
                    ? "" : "，错误 " + result.getError().getSafeMessage()) + "）");
        }
        Object output = parse(result.getContent());
        if (output == null || (output instanceof String && ((String) output).trim().isEmpty())) {
            throw new IllegalStateException("工作流 AGENT 节点回复为空（节点 " + nodeId + "）");
        }
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("output", output);
        return response;
    }

    private String userInput(ExecutionScope scope) {
        StringBuilder builder = new StringBuilder();
        builder.append(promptProvider.prompt(promptId));
        for (Map.Entry<String, Object> entry : promptVars.entrySet()) {
            Object resolved = WorkflowVariableResolver.resolveValue(entry.getValue(), scope, nodeId);
            builder.append('\n').append("## ").append(entry.getKey()).append('\n')
                    .append(WorkflowVariableResolver.stringify(resolved));
        }
        return builder.toString();
    }

    /**
     * 尝试把模型回复解析为 JSON 对象（剥离代码围栏与首尾空白），失败则返回原始文本。
     */
    private static Object parse(String content) {
        if (content == null) {
            return null;
        }
        String cleaned = stripFence(content.trim());
        try {
            JsonNode node = JSON.readTree(cleaned);
            if (node != null && node.isObject()) {
                return JSON.convertValue(node, LinkedHashMap.class);
            }
        } catch (IOException ignored) {
            // 非 JSON 文本按原始文本回传
        }
        return content;
    }

    private static String stripFence(String text) {
        if (text.startsWith("```")) {
            int newline = text.indexOf('\n');
            if (newline >= 0) {
                text = text.substring(newline + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
        }
        return text.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
