package com.clara.core.workflow;

import com.clara.dto.workflow.ExecutionScope;

import java.util.Map;

/**
 * 工作流动作：执行一个动作节点，读取作用域变量并返回输出变量。
 */
public interface IWorkflowAction {

    /**
     * 执行动作。
     *
     * @param scope 执行作用域快照
     * @return 输出变量映射，可为 null（表示无输出）
     */
    Map<String, Object> execute(ExecutionScope scope);
}
