package com.clara.workflow.node;

import com.clara.utils.StringUtils;
import com.clara.core.workflow.IWorkflowAction;
import com.clara.core.workflow.IWorkflowNode;
import com.clara.enums.workflow.WorkflowNodeTypeEnum;
import lombok.Data;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 动作节点：承载 AGENT / TOOL 动作、输出键与幂等键。
 */
@Data
public final class ActionNode implements IWorkflowNode {

    private final String id;
    private final WorkflowNodeTypeEnum type;
    private final Set<String> outputKeys;
    private final String idempotencyKey;
    private final IWorkflowAction action;

    ActionNode(String id, WorkflowNodeTypeEnum type, Set<String> outputKeys, String idempotencyKey, IWorkflowAction action) {
        if (StringUtils.isBlank(id)) {
            throw new IllegalArgumentException("node id must not be blank");
        }
        if (StringUtils.isBlank(idempotencyKey) || action == null) {
            throw new IllegalArgumentException("idempotencyKey and action are required");
        }
        this.id = id;
        this.type = type;
        this.outputKeys = outputKeys == null ? Collections.emptySet() : Collections.unmodifiableSet(new LinkedHashSet<>(outputKeys));
        this.idempotencyKey = idempotencyKey;
        this.action = action;
    }

    @Override
    public List<IWorkflowNode> children() {
        return Collections.emptyList();
    }

}
