package com.clara.workflow.node;

import com.clara.core.workflow.IWorkflowAction;
import com.clara.core.workflow.IWorkflowNode;
import com.clara.enums.workflow.WorkflowNodeTypeEnum;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 动作节点：承载 AGENT / TOOL 动作、输出键与幂等键。
 */
public final class ActionNode implements IWorkflowNode {

    private final String id;
    private final WorkflowNodeTypeEnum type;
    private final Set<String> outputKeys;
    private final String idempotencyKey;
    private final IWorkflowAction action;

    ActionNode(String id, WorkflowNodeTypeEnum type, Set<String> outputKeys,
            String idempotencyKey, IWorkflowAction action) {
        if (blank(id)) {
            throw new IllegalArgumentException("node id must not be blank");
        }
        if (blank(idempotencyKey) || action == null) {
            throw new IllegalArgumentException("idempotencyKey and action are required");
        }
        this.id = id;
        this.type = type;
        this.outputKeys = outputKeys == null
                ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(outputKeys));
        this.idempotencyKey = idempotencyKey;
        this.action = action;
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
        return Collections.emptyList();
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public IWorkflowAction getAction() {
        return action;
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
