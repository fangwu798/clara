package com.clara.dto.workflow;

import com.clara.core.workflow.IWorkflowNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 工作流定义：描述工作流的结构与元数据（标识、版本、入参 / 出参 Schema 与根节点）。
 *
 * <p>构造时通过 {@link IWorkflowNode#children()} 递归收集节点并校验：节点 ID 唯一、图无环。无租户概念。
 */
public final class WorkflowDefinition {

    private final String workflowId;
    private final String version;
    private final String inputSchema;
    private final String outputSchema;
    private final IWorkflowNode root;
    private final List<IWorkflowNode> nodes;

    /**
     * 创建工作流定义。
     *
     * @param workflowId   工作流标识，不可为空白
     * @param version      版本，不可为空白
     * @param inputSchema  入参 Schema，可为 null
     * @param outputSchema 出参 Schema，可为 null
     * @param root         根节点，不可为 null
     * @throws IllegalArgumentException workflowId、version 或 root 不合法，或图中存在环 / 重复节点 ID 时
     */
    public WorkflowDefinition(String workflowId, String version, String inputSchema,
            String outputSchema, IWorkflowNode root) {
        if (blank(workflowId) || blank(version) || root == null) {
            throw new IllegalArgumentException("workflowId, version and root are required");
        }
        this.workflowId = workflowId;
        this.version = version;
        this.inputSchema = inputSchema;
        this.outputSchema = outputSchema;
        this.root = root;
        List<IWorkflowNode> collected = new ArrayList<IWorkflowNode>();
        collect(root, new HashSet<String>(), new HashSet<IWorkflowNode>(), collected);
        this.nodes = Collections.unmodifiableList(collected);
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getVersion() {
        return version;
    }

    public String getInputSchema() {
        return inputSchema;
    }

    public String getOutputSchema() {
        return outputSchema;
    }

    public IWorkflowNode getRoot() {
        return root;
    }

    public List<IWorkflowNode> getNodes() {
        return nodes;
    }

    private static void collect(IWorkflowNode node, Set<String> ids, Set<IWorkflowNode> path,
            List<IWorkflowNode> result) {
        if (!path.add(node)) {
            throw new IllegalArgumentException("workflow graph contains a cycle");
        }
        if (!ids.add(node.getId())) {
            throw new IllegalArgumentException("duplicate node id: " + node.getId());
        }
        result.add(node);
        for (IWorkflowNode child : node.children()) {
            collect(child, ids, path, result);
        }
        path.remove(node);
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
