package com.clara.workflow.node;

import com.clara.utils.StringUtils;
import com.clara.core.workflow.IWorkflowNode;
import com.clara.enums.workflow.WorkflowNodeTypeEnum;
import lombok.Data;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 人工输入节点：承载等待输入的键。
 */
@Data
public final class HumanNode implements IWorkflowNode {

    private final String id;
    private final String inputKey;
    private final Set<String> outputKeys;

    HumanNode(String id, String inputKey) {
        if (StringUtils.isBlank(id)) {
            throw new IllegalArgumentException("node id must not be blank");
        }
        if (StringUtils.isBlank(inputKey)) {
            throw new IllegalArgumentException("inputKey must not be blank");
        }
        this.id = id;
        this.inputKey = inputKey;
        Set<String> outputs = new LinkedHashSet<>();
        outputs.add(inputKey);
        this.outputKeys = Collections.unmodifiableSet(outputs);
    }

    @Override
    public WorkflowNodeTypeEnum getType() {
        return WorkflowNodeTypeEnum.HUMAN;
    }

    @Override
    public List<IWorkflowNode> children() {
        return Collections.emptyList();
    }

}
