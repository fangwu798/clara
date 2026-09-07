package com.clara.planner;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 封装任务执行操作的执行结果。
 */
public final class TaskExecutionResult {
    private final Map<String, Object> outputs;
    private final BudgetAmount actualUsage;
    public TaskExecutionResult(Map<String, Object> outputs, BudgetAmount actualUsage) {
        if (actualUsage == null) { throw new IllegalArgumentException("actualUsage must not be null"); }
        this.outputs = outputs == null ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(outputs));
        this.actualUsage = actualUsage;
    }
    public Map<String, Object> getOutputs() { return outputs; }
    public BudgetAmount getActualUsage() { return actualUsage; }
}
