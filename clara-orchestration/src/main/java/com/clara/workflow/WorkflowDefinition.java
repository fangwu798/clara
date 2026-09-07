package com.clara.workflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 定义工作流的结构与元数据。
 */
public final class WorkflowDefinition {
    private final String workflowId;
    private final String version;
    private final String inputSchema;
    private final String outputSchema;
    private final WorkflowNode root;
    private final List<WorkflowNode> nodes;

    public WorkflowDefinition(String workflowId, String version, String inputSchema,
            String outputSchema, WorkflowNode root) {
        if (blank(workflowId) || blank(version) || root == null) {
            throw new IllegalArgumentException("workflowId, version and root are required");
        }
        this.workflowId = workflowId; this.version = version;
        this.inputSchema = inputSchema; this.outputSchema = outputSchema; this.root = root;
        List<WorkflowNode> collected = new ArrayList<WorkflowNode>();
        collect(root, new HashSet<String>(), new HashSet<WorkflowNode>(), collected);
        this.nodes = Collections.unmodifiableList(collected);
    }

    public String getWorkflowId() { return workflowId; }
    public String getVersion() { return version; }
    public String getInputSchema() { return inputSchema; }
    public String getOutputSchema() { return outputSchema; }
    public WorkflowNode getRoot() { return root; }
    public List<WorkflowNode> getNodes() { return nodes; }

    private static void collect(WorkflowNode node, Set<String> ids, Set<WorkflowNode> path,
            List<WorkflowNode> result) {
        if (!path.add(node)) { throw new IllegalArgumentException("workflow graph contains a cycle"); }
        if (!ids.add(node.getId())) { throw new IllegalArgumentException("duplicate node id: " + node.getId()); }
        result.add(node);
        if (node instanceof WorkflowNodes.CompositeNode) {
            for (WorkflowNode child : ((WorkflowNodes.CompositeNode) node).children) collect(child, ids, path, result);
        } else if (node instanceof WorkflowNodes.ConditionNode) {
            WorkflowNodes.ConditionNode value = (WorkflowNodes.ConditionNode) node;
            collect(value.whenTrue, ids, path, result);
            if (value.whenFalse != null) collect(value.whenFalse, ids, path, result);
        } else if (node instanceof WorkflowNodes.LoopNode) {
            collect(((WorkflowNodes.LoopNode) node).body, ids, path, result);
        }
        path.remove(node);
    }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
