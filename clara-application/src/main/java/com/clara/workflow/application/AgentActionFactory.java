package com.clara.workflow.application;

import com.clara.core.skill.IPromptProvider;
import com.clara.core.workflow.IWorkflowAction;
import com.clara.core.workflow.IWorkflowActionFactory;
import com.clara.dto.workflow.WorkflowActionSpec;
import com.clara.orchestration.DefaultConversationOrchestrator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AGENT 动作工厂：把配置化的 AGENT 节点描述转换为 {@link AgentWorkflowAction}。
 *
 * <p>是什么、在哪一层：application 工作流装配层对 core {@link IWorkflowActionFactory} 的实现
 * （包 {@code com.clara.workflow.application}），由装配层创建并注入工作流定义加载器。
 *
 * <p>属性约定：必需 {@code prompt}（提示词标识），可选 {@code promptVars}（对象，变量名到模板的映射）。
 * 失败语义：{@code prompt} 缺失或 {@code promptVars} 类型不符时抛 {@link IllegalArgumentException}。
 */
public final class AgentActionFactory implements IWorkflowActionFactory {

    private final DefaultConversationOrchestrator orchestrator;
    private final IPromptProvider promptProvider;
    private final WorkflowModelContext modelContext;

    /**
     * 创建 AGENT 动作工厂。
     *
     * @param orchestrator   对话编排器，不可为 null
     * @param promptProvider 提示词提供器，不可为 null
     * @param modelContext   工作流模型上下文，不可为 null
     * @throws IllegalArgumentException 任一项为 null 时
     */
    public AgentActionFactory(DefaultConversationOrchestrator orchestrator, IPromptProvider promptProvider,
            WorkflowModelContext modelContext) {
        if (orchestrator == null || promptProvider == null || modelContext == null) {
            throw new IllegalArgumentException("agent factory dependencies must not be null");
        }
        this.orchestrator = orchestrator;
        this.promptProvider = promptProvider;
        this.modelContext = modelContext;
    }

    @Override
    public boolean supports(String actionKind) {
        return "AGENT".equalsIgnoreCase(actionKind);
    }

    @Override
    public IWorkflowAction create(WorkflowActionSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spec must not be null");
        }
        String prompt = asText(spec.getAttribute("prompt"));
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new IllegalArgumentException("AGENT 节点缺少 prompt：" + spec.getNodeId());
        }
        Map<String, Object> promptVars = asMap(spec.getAttribute("promptVars"), spec.getNodeId(), "promptVars");
        return new AgentWorkflowAction(orchestrator, promptProvider, modelContext, spec.getNodeId(), prompt.trim(),
                promptVars);
    }

    private static String asText(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof String ? (String) value : String.valueOf(value);
    }

    private static Map<String, Object> asMap(Object value, String nodeId, String field) {
        if (value == null) {
            return new LinkedHashMap<String, Object>();
        }
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("节点 " + nodeId + " 的 " + field + " 必须是对象");
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }
}
