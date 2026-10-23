package com.clara.workflow;

import com.clara.api.IBuiltinTool;
import com.clara.context.ConvContext;
import com.clara.workflow.runtime.WorkflowRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * 内置工具 {@code run_workflow}：让模型以自然语言触发的「配置化工作流」入口。
 *
 * <p>是什么、在哪一层：application 工作流装配层（包 {@code com.clara.workflow.application}）对
 * {@link IBuiltinTool} 的实现，运行类型为 BUILTIN。宿主把它注册进内置工具执行器，模型在工具往返中即可
 * 按名调用工作流（如读取本地目录代码并整理技术文档）。
 *
 * <p>入参 JSON：{@code workflow}（必填，工作流标识）、{@code instanceId}（可选，续接等待人工输入的实例）、
 * {@code inputs}（可选对象，传给工作流的输入变量或人工输入值）。
 * 失败语义：缺少 {@code workflow} 或入参非法抛 {@link IllegalArgumentException}；工作流执行失败按运行器约定
 * 向上抛。
 */
public final class WorkflowRunTool implements IBuiltinTool {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final WorkflowRunner runner;

    /** 会话级待确认工作流登记表；用于在故障转移或历史压缩后仍能提示模型正确续接。 */
    private final PendingWorkflowRegistry pendingWorkflows;

    /**
     * 创建内置工具。
     *
     * @param runner 工作流运行器，不可为 null
     * @throws IllegalArgumentException runner 为 null 时
     */
    public WorkflowRunTool(WorkflowRunner runner) {
        this(runner, new PendingWorkflowRegistry());
    }

    /**
     * 创建内置工具，并把暂停实例登记到指定的会话级登记表。
     *
     * @param runner          工作流运行器，不可为 null
     * @param pendingWorkflows 待确认工作流登记表，为 null 时使用独立实例
     * @throws IllegalArgumentException runner 为 null 时
     */
    public WorkflowRunTool(WorkflowRunner runner, PendingWorkflowRegistry pendingWorkflows) {
        if (runner == null) {
            throw new IllegalArgumentException("runner must not be null");
        }
        this.runner = runner;
        this.pendingWorkflows = pendingWorkflows == null ? new PendingWorkflowRegistry() : pendingWorkflows;
    }

    @Override
    public String name() {
        return "run_workflow";
    }

    @Override
    public String execute(String argumentsJson) {
        JsonNode root = parse(argumentsJson);
        JsonNode workflowNode = root == null ? null : root.get("workflow");
        String workflow = workflowNode != null && workflowNode.isTextual() ? workflowNode.textValue() : null;
        if (workflow == null || workflow.trim().isEmpty()) {
            throw new IllegalArgumentException("run_workflow 缺少 workflow 参数");
        }
        JsonNode inputsNode = root.get("inputs");
        String inputsJson = inputsNode == null || inputsNode.isNull() ? null : inputsNode.toString();
        JsonNode instanceNode = root.get("instanceId");
        String instanceId = instanceNode != null && instanceNode.isTextual() ? instanceNode.textValue() : null;
        boolean resuming = instanceId != null && !instanceId.trim().isEmpty();
        String conversationId = conversationId();
        String output;
        try {
            output = resuming
                    ? runner.resume(workflow.trim(), instanceId.trim(), inputsJson)
                    : runner.run(workflow.trim(), inputsJson);
        } catch (RuntimeException failure) {
            // 新任务失败时清掉旧登记，避免下一轮提示模型续接一个已经失效的实例；续接失败则保留，便于重试。
            if (!resuming) {
                pendingWorkflows.clear(conversationId);
            }
            throw failure;
        }
        pendingWorkflows.update(conversationId, workflow.trim(), output);
        return output;
    }

    /**
     * 当前会话标识；工具不在会话上下文中执行时返回 null，此时不登记待确认状态。
     */
    private static String conversationId() {
        ConvContext current = ConvContext.current();
        return current == null ? null : current.getConvId();
    }

    private static JsonNode parse(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.trim().isEmpty()) {
            throw new IllegalArgumentException("run_workflow 缺少入参");
        }
        try {
            return JSON.readTree(argumentsJson);
        } catch (IOException e) {
            throw new IllegalArgumentException("run_workflow 入参不是合法 JSON：" + e.getMessage(), e);
        }
    }
}
