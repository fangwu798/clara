package com.clara.workflow.node;

import com.clara.utils.StringUtils;
import com.clara.core.workflow.IScopeCondition;
import com.clara.core.workflow.IWorkflowNode;
import com.clara.enums.workflow.WorkflowNodeTypeEnum;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 循环节点：承载循环体、最大迭代次数与退出条件。
 */
public final class LoopNode implements IWorkflowNode {

    private final String id;
    private final IWorkflowNode body;
    private final int maxIterations;
    private final IScopeCondition exitCondition;

    LoopNode(String id, IWorkflowNode body, int maxIterations, IScopeCondition exitCondition) {
        if (StringUtils.isBlank(id)) {
            throw new IllegalArgumentException("node id must not be blank");
        }
        this.id = id;
        this.body = body;
        this.maxIterations = maxIterations;
        this.exitCondition = exitCondition;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public WorkflowNodeTypeEnum getType() {
        return WorkflowNodeTypeEnum.LOOP;
    }

    @Override
    public Set<String> getOutputKeys() {
        return body.getOutputKeys();
    }

    @Override
    public List<IWorkflowNode> children() {
        return Collections.singletonList(body);
    }

    public IWorkflowNode getBody() {
        return body;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public IScopeCondition getExitCondition() {
        return exitCondition;
    }

}
