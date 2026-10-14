package com.clara.workflow.runtime;

import com.clara.core.skill.IToolInvoker;
import com.clara.core.workflow.IWorkflowAction;
import com.clara.dto.tool.ToolCall;
import com.clara.dto.tool.ToolCallFunction;
import com.clara.dto.tool.ToolResult;
import com.clara.dto.workflow.ExecutionScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TOOL 工作流动作：按参数模板调用一次工具，并把结果文本作为输出。
 *
 * <p>是什么、在哪一层：clara-workflow 对 core {@link IWorkflowAction} 的实现
 * （包 {@code com.clara.workflow.runtime}），由 {@link ToolActionFactory} 创建，供工作流引擎在
 * TOOL 节点执行。
 *
 * <p>执行流程：对参数模板递归解析变量 → Jackson 序列化为入参 JSON → 构造 {@link ToolCall} 经
 * {@link IToolInvoker} 调用 → 返回 {@code {"output": <结果文本>}} 供节点落位。
 *
 * <p>失败语义：参数序列化失败抛 {@link IllegalArgumentException}；工具调用异常直接向上抛，由引擎把节点
 * 标记为失败。
 */
public final class ToolWorkflowAction implements IWorkflowAction {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final IToolInvoker toolInvoker;
    private final String nodeId;
    private final String toolName;
    private final Map<String, Object> argumentsTemplate;

    /**
     * 创建 TOOL 动作。
     *
     * @param toolInvoker       工具调用入口，不可为 null
     * @param nodeId            节点 id，不可为空白
     * @param toolName          工具名，不可为空白
     * @param argumentsTemplate 参数模板，可为 null（视为空）
     * @throws IllegalArgumentException 依赖或必填项非法时
     */
    public ToolWorkflowAction(IToolInvoker toolInvoker, String nodeId, String toolName,
            Map<String, Object> argumentsTemplate) {
        if (toolInvoker == null) {
            throw new IllegalArgumentException("toolInvoker must not be null");
        }
        if (blank(nodeId) || blank(toolName)) {
            throw new IllegalArgumentException("nodeId and toolName are required");
        }
        this.toolInvoker = toolInvoker;
        this.nodeId = nodeId;
        this.toolName = toolName;
        this.argumentsTemplate = argumentsTemplate == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(argumentsTemplate);
    }

    @Override
    public Map<String, Object> execute(ExecutionScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : argumentsTemplate.entrySet()) {
            arguments.put(entry.getKey(),
                    WorkflowVariableResolver.resolveValue(entry.getValue(), scope, nodeId));
        }
        String argumentsJson;
        try {
            argumentsJson = JSON.writeValueAsString(arguments);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("工具节点参数序列化失败（节点 " + nodeId + "）：" + e.getMessage(), e);
        }
        ToolCall call = new ToolCall();
        call.setId(nodeId);
        call.setType("function");
        ToolCallFunction function = new ToolCallFunction();
        function.setName(toolName);
        function.setArguments(argumentsJson);
        call.setFunction(function);

        ToolResult result = toolInvoker.invoke(call);
        Map<String, Object> output = new LinkedHashMap<String, Object>();
        output.put("output", result.getOutput());
        return output;
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
