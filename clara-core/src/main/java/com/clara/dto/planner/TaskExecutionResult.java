package com.clara.dto.planner;

import lombok.Data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务执行结果：封装任务输出的变量映射与实际预算用量。
 */
@Data
public final class TaskExecutionResult {

    private final Map<String, Object> outputs;
    private final BudgetAmount actualBudget;

    /**
     * 创建任务执行结果。
     *
     * @param outputs       任务输出的变量映射，不可为 null
     * @param actualBudget  实际预算用量，不可为 null
     * @throws IllegalArgumentException 任一字段为 null 时
     */
    public TaskExecutionResult(Map<String, Object> outputs, BudgetAmount actualBudget) {
        if (outputs == null || actualBudget == null) {
            throw new IllegalArgumentException("task execution result is invalid");
        }
        this.outputs = Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
        this.actualBudget = actualBudget;
    }
}
