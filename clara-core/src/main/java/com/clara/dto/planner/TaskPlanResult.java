package com.clara.dto.planner;

import com.clara.enums.planner.PlanStatusEnum;
import com.clara.enums.planner.TaskNodeStatusEnum;
import lombok.Data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务计划执行结果：封装计划级执行结果（计划、状态、各任务状态、输出映射与步数）。
 */
@Data
public final class TaskPlanResult {

    private final TaskPlan plan;
    private final PlanStatusEnum status;
    private final Map<String, TaskNodeStatusEnum> taskStatuses;
    private final Map<String, Object> outputs;
    private final int steps;

    /**
     * 创建任务计划执行结果。
     *
     * @param plan         执行过的任务计划，不可为 null
     * @param status       计划状态，不可为 null
     * @param taskStatuses 各任务状态映射，不可为 null
     * @param outputs      合并后的输出变量映射，不可为 null
     * @param steps        实际执行步数，不可为负
     * @throws IllegalArgumentException 任一字段不合法时
     */
    public TaskPlanResult(TaskPlan plan, PlanStatusEnum status,
            Map<String, TaskNodeStatusEnum> taskStatuses, Map<String, Object> outputs, int steps) {
        if (plan == null || status == null || taskStatuses == null || outputs == null || steps < 0) {
            throw new IllegalArgumentException("task plan result is invalid");
        }
        this.plan = plan;
        this.status = status;
        this.taskStatuses = Collections.unmodifiableMap(
                new LinkedHashMap<String, TaskNodeStatusEnum>(taskStatuses));
        this.outputs = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(outputs));
        this.steps = steps;
    }
}
