package com.clara.workflow.node;

import com.clara.core.workflow.IWorkflowNode;
import com.clara.enums.workflow.WorkflowNodeTypeEnum;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 组合节点：承载 SEQUENCE / PARALLEL 子节点列表。
 */
public final class CompositeNode implements IWorkflowNode {

    private final String id;
    private final WorkflowNodeTypeEnum type;
    private final List<IWorkflowNode> children;
    private final Set<String> outputKeys;

    CompositeNode(String id, WorkflowNodeTypeEnum type, List<IWorkflowNode> children) {
        if (blank(id)) {
            throw new IllegalArgumentException("node id must not be blank");
        }
        List<IWorkflowNode> copied = requireChildren(children);
        this.id = id;
        this.type = type;
        this.children = Collections.unmodifiableList(new ArrayList<IWorkflowNode>(copied));
        this.outputKeys = union(copied);
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public WorkflowNodeTypeEnum getType() {
        return type;
    }

    @Override
    public Set<String> getOutputKeys() {
        return outputKeys;
    }

    @Override
    public List<IWorkflowNode> children() {
        return children;
    }

    public List<IWorkflowNode> getChildren() {
        return children;
    }

    private static List<IWorkflowNode> requireChildren(List<IWorkflowNode> children) {
        if (children == null || children.isEmpty() || children.contains(null)) {
            throw new IllegalArgumentException("children must not be empty");
        }
        return children;
    }

    private static Set<String> union(List<IWorkflowNode> nodes) {
        Set<String> values = new LinkedHashSet<String>();
        for (IWorkflowNode node : nodes) {
            values.addAll(node.getOutputKeys());
        }
        return Collections.unmodifiableSet(values);
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
