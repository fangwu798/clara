package com.clara.workflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 表示。
 */
public final class WorkflowNodes {
    private WorkflowNodes() { }

    public static WorkflowNode action(String id, WorkflowNodeType type, Set<String> outputKeys,
            String idempotencyKey, WorkflowAction action) {
        if (type != WorkflowNodeType.AGENT && type != WorkflowNodeType.TOOL) {
            throw new IllegalArgumentException("action type must be AGENT or TOOL");
        }
        return new ActionNode(id, type, outputKeys, idempotencyKey, action);
    }
    public static WorkflowNode sequence(String id, List<WorkflowNode> children) {
        return new CompositeNode(id, WorkflowNodeType.SEQUENCE, children);
    }
    public static WorkflowNode parallel(String id, List<WorkflowNode> children) {
        Set<String> outputs = new LinkedHashSet<String>();
        for (WorkflowNode child : requireChildren(children)) {
            for (String key : child.getOutputKeys()) {
                if (!outputs.add(key)) { throw new IllegalArgumentException("parallel output keys must be unique: " + key); }
            }
        }
        return new CompositeNode(id, WorkflowNodeType.PARALLEL, children);
    }
    public static WorkflowNode condition(String id, ScopeCondition condition,
            WorkflowNode whenTrue, WorkflowNode whenFalse) {
        if (condition == null || whenTrue == null) { throw new IllegalArgumentException("condition and true branch are required"); }
        return new ConditionNode(id, condition, whenTrue, whenFalse);
    }
    public static WorkflowNode loop(String id, WorkflowNode body, int maxIterations,
            ScopeCondition exitCondition) {
        if (body == null || maxIterations <= 0) { throw new IllegalArgumentException("loop body and positive maxIterations are required"); }
        return new LoopNode(id, body, maxIterations, exitCondition);
    }
    public static WorkflowNode human(String id, String inputKey) {
        return new HumanNode(id, inputKey);
    }

    /**
     * 表示。
     */
    static abstract class BaseNode implements WorkflowNode {
        private final String id; private final WorkflowNodeType type; private final Set<String> outputs;
        BaseNode(String id, WorkflowNodeType type, Set<String> outputs) {
            if (blank(id)) { throw new IllegalArgumentException("node id must not be blank"); }
            this.id = id; this.type = type;
            this.outputs = Collections.unmodifiableSet(new LinkedHashSet<String>(outputs));
        }
        public String getId() { return id; }
        public WorkflowNodeType getType() { return type; }
        public Set<String> getOutputKeys() { return outputs; }
    }
    /**
     * 表示。
     */
    static final class ActionNode extends BaseNode {
        final String idempotencyKey; final WorkflowAction action;
        ActionNode(String id, WorkflowNodeType type, Set<String> outputs, String key, WorkflowAction action) {
            super(id, type, outputs == null ? Collections.<String>emptySet() : outputs);
            if (blank(key) || action == null) { throw new IllegalArgumentException("idempotencyKey and action are required"); }
            idempotencyKey = key; this.action = action;
        }
    }
    /**
     * 表示。
     */
    static final class CompositeNode extends BaseNode {
        final List<WorkflowNode> children;
        CompositeNode(String id, WorkflowNodeType type, List<WorkflowNode> children) {
            super(id, type, union(requireChildren(children)));
            this.children = Collections.unmodifiableList(new ArrayList<WorkflowNode>(children));
        }
    }
    /**
     * 表示。
     */
    static final class ConditionNode extends BaseNode {
        final ScopeCondition condition; final WorkflowNode whenTrue; final WorkflowNode whenFalse;
        ConditionNode(String id, ScopeCondition condition, WorkflowNode whenTrue, WorkflowNode whenFalse) {
            super(id, WorkflowNodeType.CONDITION, unionBranches(whenTrue, whenFalse));
            this.condition = condition; this.whenTrue = whenTrue; this.whenFalse = whenFalse;
        }
    }
    /**
     * 表示。
     */
    static final class LoopNode extends BaseNode {
        final WorkflowNode body; final int maxIterations; final ScopeCondition exitCondition;
        LoopNode(String id, WorkflowNode body, int maxIterations, ScopeCondition exitCondition) {
            super(id, WorkflowNodeType.LOOP, body.getOutputKeys());
            this.body = body; this.maxIterations = maxIterations; this.exitCondition = exitCondition;
        }
    }
    /**
     * 表示。
     */
    static final class HumanNode extends BaseNode {
        final String inputKey;
        HumanNode(String id, String inputKey) {
            super(id, WorkflowNodeType.HUMAN, singleton(inputKey));
            if (blank(inputKey)) { throw new IllegalArgumentException("inputKey must not be blank"); }
            this.inputKey = inputKey;
        }
    }

    private static List<WorkflowNode> requireChildren(List<WorkflowNode> children) {
        if (children == null || children.isEmpty() || children.contains(null)) {
            throw new IllegalArgumentException("children must not be empty");
        }
        return children;
    }
    private static Set<String> union(List<WorkflowNode> nodes) {
        Set<String> values = new LinkedHashSet<String>();
        for (WorkflowNode node : nodes) values.addAll(node.getOutputKeys());
        return values;
    }
    private static Set<String> unionBranches(WorkflowNode one, WorkflowNode two) {
        Set<String> values = new LinkedHashSet<String>(one.getOutputKeys());
        if (two != null) values.addAll(two.getOutputKeys()); return values;
    }
    private static Set<String> singleton(String value) {
        Set<String> result = new LinkedHashSet<String>(); if (value != null) result.add(value); return result;
    }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
