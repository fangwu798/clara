package com.clara.workflow.runtime;

import com.clara.core.skill.IToolInvoker;
import com.clara.core.workflow.IWorkflowAction;
import com.clara.core.workflow.IWorkflowActionFactory;
import com.clara.dto.workflow.WorkflowActionSpec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TOOL 动作工厂：把配置化的 TOOL 节点描述转换为 {@link ToolWorkflowAction}。
 *
 * <p>是什么、在哪一层：clara-workflow 对 core {@link IWorkflowActionFactory} 的实现
 * （包 {@code com.clara.workflow.runtime}），由宿主装配时创建并注入工作流定义加载器。
 *
 * <p>属性约定：必需 {@code tool}（工具名），可选 {@code arguments}（对象，参数模板）。失败语义：
 * {@code tool} 缺失或 {@code arguments} 类型不符时抛 {@link IllegalArgumentException}。
 */
public final class ToolActionFactory implements IWorkflowActionFactory {

    private final IToolInvoker toolInvoker;

    /**
     * 创建 TOOL 动作工厂。
     *
     * @param toolInvoker 工具调用入口，不可为 null
     * @throws IllegalArgumentException toolInvoker 为 null 时
     */
    public ToolActionFactory(IToolInvoker toolInvoker) {
        if (toolInvoker == null) {
            throw new IllegalArgumentException("toolInvoker must not be null");
        }
        this.toolInvoker = toolInvoker;
    }

    @Override
    public boolean supports(String actionKind) {
        return "TOOL".equalsIgnoreCase(actionKind);
    }

    @Override
    public IWorkflowAction create(WorkflowActionSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spec must not be null");
        }
        Object toolValue = spec.getAttribute("tool");
        String tool = toolValue instanceof String ? (String) toolValue
                : (toolValue == null ? null : String.valueOf(toolValue));
        if (tool == null || tool.trim().isEmpty()) {
            throw new IllegalArgumentException("TOOL 节点缺少 tool：" + spec.getNodeId());
        }
        Map<String, Object> arguments = asMap(spec.getAttribute("arguments"), spec.getNodeId());
        return new ToolWorkflowAction(toolInvoker, spec.getNodeId(), tool.trim(), arguments);
    }

    private static Map<String, Object> asMap(Object value, String nodeId) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("节点 " + nodeId + " 的 arguments 必须是对象");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }
}
