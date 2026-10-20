package com.clara.workflow.node;

import com.clara.utils.StringUtils;
import com.clara.core.workflow.IScopeCondition;
import com.clara.core.workflow.IWorkflowNode;
import com.clara.enums.workflow.WorkflowNodeTypeEnum;
import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 条件分支节点：承载条件与真假分支。
 */
@Data
public final class ConditionNode implements IWorkflowNode {

    private final String id;
    private final IScopeCondition condition;
    private final IWorkflowNode whenTrue;
    private final IWorkflowNode whenFalse;
    private final Set<String> outputKeys;

    ConditionNode(String id, IScopeCondition condition, IWorkflowNode whenTrue, IWorkflowNode whenFalse) {
        if (StringUtils.isBlank(id)) {
            throw new IllegalArgumentException("node id must not be blank");
        }
        this.id = id;
        this.condition = condition;
        this.whenTrue = whenTrue;
        this.whenFalse = whenFalse;
        this.outputKeys = unionBranches(whenTrue, whenFalse);
    }

    @Override
    public WorkflowNodeTypeEnum getType() {
        return WorkflowNodeTypeEnum.CONDITION;
    }

    @Override
    public List<IWorkflowNode> children() {
        List<IWorkflowNode> result = new ArrayList<>();
        result.add(whenTrue);
        if (whenFalse != null) {
            result.add(whenFalse);
        }
        return Collections.unmodifiableList(result);
    }

    private static Set<String> unionBranches(IWorkflowNode one, IWorkflowNode two) {
        Set<String> values = new LinkedHashSet<>(one.getOutputKeys());
        if (two != null) {
            values.addAll(two.getOutputKeys());
        }
        return Collections.unmodifiableSet(values);
    }

}
