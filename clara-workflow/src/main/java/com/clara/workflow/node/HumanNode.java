package com.clara.workflow.node;

import com.clara.core.workflow.IWorkflowNode;
import com.clara.enums.workflow.WorkflowNodeTypeEnum;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 人工输入节点：承载等待输入的键。
 */
public final class HumanNode implements IWorkflowNode {

    private final String id;
    private final String inputKey;
    private final Set<String> outputKeys;

    HumanNode(String id, String inputKey) {
        if (blank(id)) {
            throw new IllegalArgumentException("node id must not be blank");
        }
        if (blank(inputKey)) {
            throw new IllegalArgumentException("inputKey must not be blank");
        }
        this.id = id;
        this.inputKey = inputKey;
        Set<String> outputs = new LinkedHashSet<String>();
        outputs.add(inputKey);
        this.outputKeys = Collections.unmodifiableSet(outputs);
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public WorkflowNodeTypeEnum getType() {
        return WorkflowNodeTypeEnum.HUMAN;
    }

    @Override
    public Set<String> getOutputKeys() {
        return outputKeys;
    }

    @Override
    public List<IWorkflowNode> children() {
        return Collections.emptyList();
    }

    public String getInputKey() {
        return inputKey;
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
