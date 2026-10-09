package com.clara.core.workflow;

import com.clara.enums.workflow.WorkflowNodeTypeEnum;

import java.util.List;
import java.util.Set;

/**
 * 工作流节点：工作流定义图中的单个节点，描述节点的标识、类型、输出键与子节点。
 *
 * <p>core 只定义契约，具体节点形态（动作 / 顺序 / 并行 / 条件 / 循环 / 人工）由 clara-workflow 提供实现；
 * 定义图遍历通过 {@link #children()} 完成，不依赖具体节点类型。
 */
public interface IWorkflowNode {

    /**
     * 节点标识，在工作流内唯一。
     *
     * @return 节点 ID，非 null
     */
    String getId();

    /**
     * 节点类型。
     *
     * @return 节点类型，非 null
     */
    WorkflowNodeTypeEnum getType();

    /**
     * 节点输出的变量键集合。
     *
     * @return 输出键集合，非 null
     */
    Set<String> getOutputKeys();

    /**
     * 子节点列表。
     *
     * <p>组合节点返回其全部子节点；条件节点返回真 / 假分支（假分支为 null 时省略）；循环节点返回循环体；
     * 动作 / 人工等叶子节点返回空列表。
     *
     * @return 子节点列表，非 null，可为空列表
     */
    List<IWorkflowNode> children();
}
