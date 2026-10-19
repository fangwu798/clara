package com.clara.workflow.definition;

import com.clara.dto.workflow.WorkflowDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 已加载的工作流：定义本体 + 该工作流对外声明的输出变量键。
 *
 * <p>是什么、在哪一层：clara-workflow 定义加载的返回载体（包 {@code com.clara.workflow.definition}）。
 * YAML 顶层的 {@code outputs} 声明「执行结束后应回传给调用方的变量」，供运行器裁剪结果，避免把中间产物
 * （如批量读取的源码内容）整体回灌给模型。
 *
 * <p>谁创建谁调用：由 {@link WorkflowDefinitionLoader} 创建、{@link WorkflowDefinitionRegistry} 持有、
 * 运行器读取；不可变。
 */
public final class LoadedWorkflow {

    private final WorkflowDefinition definition;
    private final List<String> outputs;

    /**
     * 创建已加载的工作流。
     *
     * @param definition 工作流定义，不可为 null
     * @param outputs    对外声明的输出变量键，可为 null（视为空）
     * @throws IllegalArgumentException definition 为 null 时
     */
    public LoadedWorkflow(WorkflowDefinition definition, List<String> outputs) {
        if (definition == null) {
            throw new IllegalArgumentException("definition must not be null");
        }
        this.definition = definition;
        this.outputs = outputs == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(outputs));
    }

    public WorkflowDefinition getDefinition() {
        return definition;
    }

    public List<String> getOutputs() {
        return outputs;
    }
}
