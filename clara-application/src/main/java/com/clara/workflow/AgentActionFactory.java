package com.clara.workflow;

import com.clara.core.skill.IPromptProvider;
import com.clara.core.workflow.IWorkflowAction;
import com.clara.core.workflow.IWorkflowActionFactory;
import com.clara.dto.workflow.WorkflowActionSpec;
import com.clara.orchestration.DefaultConversationOrchestrator;
import com.clara.session.ModelRegistry;
import com.clara.workflow.runtime.AgentNodeOptions;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AGENT 动作工厂：把配置化的 AGENT 节点描述转换为 {@link AgentWorkflowAction}。
 *
 * <p>是什么、在哪一层：application 工作流装配层对 core {@link IWorkflowActionFactory} 的实现
 * （包 {@code com.clara.workflow.application}），由装配层创建并注入工作流定义加载器。
 *
 * <p>属性约定：提示词二选一——{@code prompt}（既有提示词标识）或 {@code promptText}（内联提示词正文，
 * 便于自定义工作流单文件自洽）；可选 {@code promptVars}（对象，变量名到模板的映射）；可选
 * {@code role} / {@code model} / {@code capability}（节点级模型与能力选择，见 {@link AgentNodeOptions}）。
 * 失败语义：两者都缺失、{@code promptVars} 类型不符或 {@code capability} 非法时抛
 * {@link IllegalArgumentException}。
 */
public final class AgentActionFactory implements IWorkflowActionFactory {

    private final AgentActionContext context;

    /**
     * 创建 AGENT 动作工厂。
     *
     * @param orchestrator   对话编排器，不可为 null
     * @param promptProvider 提示词提供器，不可为 null
     * @param modelRegistry  模型绑定注册表，不可为 null
     * @throws IllegalArgumentException 任一项为 null 时
     */
    public AgentActionFactory(DefaultConversationOrchestrator orchestrator, IPromptProvider promptProvider,
            ModelRegistry modelRegistry) {
        this.context = new AgentActionContext(orchestrator, promptProvider, modelRegistry);
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
        String promptText = asText(spec.getAttribute("promptText"));
        if (blank(prompt) && blank(promptText)) {
            throw new IllegalArgumentException("AGENT 节点缺少 prompt 或 promptText：" + spec.getNodeId());
        }
        Map<String, Object> promptVars = asMap(spec.getAttribute("promptVars"), spec.getNodeId(), "promptVars");
        AgentNodeOptions options = AgentNodeOptions.fromSpec(spec);
        return new AgentWorkflowAction(context, spec.getNodeId(), blank(prompt) ? null : prompt.trim(),
                promptText, promptVars, options);
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
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
