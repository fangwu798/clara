package com.clara.workflow.node;

import com.clara.core.workflow.IScopeCondition;
import com.clara.core.workflow.IWorkflowAction;
import com.clara.core.workflow.IWorkflowNode;
import com.clara.enums.workflow.WorkflowNodeTypeEnum;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 工作流节点的统一构造入口。
 */
public final class WorkflowNodes {

    private WorkflowNodes() {
    }

    public static IWorkflowNode action(String id, WorkflowNodeTypeEnum type, Set<String> outputKeys,
            String idempotencyKey, IWorkflowAction action) {
        if (type != WorkflowNodeTypeEnum.AGENT && type != WorkflowNodeTypeEnum.TOOL) {
            throw new IllegalArgumentException("action type must be AGENT or TOOL");
        }
        return new ActionNode(id, type, outputKeys, idempotencyKey, action);
    }

    public static IWorkflowNode sequence(String id, List<IWorkflowNode> children) {
        return new CompositeNode(id, WorkflowNodeTypeEnum.SEQUENCE, children);
    }

    public static IWorkflowNode parallel(String id, List<IWorkflowNode> children) {
        Set<String> outputs = new LinkedHashSet<String>();
        for (IWorkflowNode child : requireChildren(children)) {
            for (String key : child.getOutputKeys()) {
                if (!outputs.add(key)) {
                    throw new IllegalArgumentException("parallel output keys must be unique: " + key);
                }
            }
        }
        return new CompositeNode(id, WorkflowNodeTypeEnum.PARALLEL, children);
    }

    public static IWorkflowNode condition(String id, IScopeCondition condition,
            IWorkflowNode whenTrue, IWorkflowNode whenFalse) {
        if (condition == null || whenTrue == null) {
            throw new IllegalArgumentException("condition and true branch are required");
        }
        return new ConditionNode(id, condition, whenTrue, whenFalse);
    }

    public static IWorkflowNode loop(String id, IWorkflowNode body, int maxIterations,
            IScopeCondition exitCondition) {
        if (body == null || maxIterations <= 0) {
            throw new IllegalArgumentException("loop body and positive maxIterations are required");
        }
        return new LoopNode(id, body, maxIterations, exitCondition);
    }

    public static IWorkflowNode human(String id, String inputKey) {
        return new HumanNode(id, inputKey);
    }

    private static List<IWorkflowNode> requireChildren(List<IWorkflowNode> children) {
        if (children == null || children.isEmpty() || children.contains(null)) {
            throw new IllegalArgumentException("children must not be empty");
        }
        return children;
    }
}
