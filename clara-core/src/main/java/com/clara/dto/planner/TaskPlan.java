package com.clara.dto.planner;

import com.clara.enums.planner.PlanStatusEnum;
import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 任务计划：描述一次执行的目标、版本、任务列表、约束与状态。
 *
 * <p>构造时校验任务 ID 唯一；依赖的 DAG 合法性由 clara-workflow 的 {@code PlanValidator} 负责。
 */
@Data
public final class TaskPlan {

    private final String goal;
    private final int version;
    private final List<TaskNode> tasks;
    private final PlanLimits limits;
    private final PlanStatusEnum status;

    /**
     * 创建任务计划。
     *
     * @param goal    计划目标，不可为空白
     * @param version 计划版本，不可为负
     * @param tasks   任务列表，不可为 null 或空
     * @param limits  计划约束，不可为 null
     * @param status  计划状态，不可为 null
     * @throws IllegalArgumentException 任一字段不合法或任务 ID 重复时
     */
    public TaskPlan(String goal, int version, List<TaskNode> tasks, PlanLimits limits,
            PlanStatusEnum status) {
        if (blank(goal) || version < 0 || tasks == null || tasks.isEmpty()
                || limits == null || status == null) {
            throw new IllegalArgumentException("task plan is invalid");
        }
        List<TaskNode> copied = new ArrayList<>(tasks.size());
        Set<String> ids = new HashSet<>();
        for (TaskNode task : tasks) {
            if (task == null || !ids.add(task.getTaskId())) {
                throw new IllegalArgumentException("task IDs must be unique");
            }
            copied.add(task);
        }
        this.goal = goal;
        this.version = version;
        this.tasks = Collections.unmodifiableList(copied);
        this.limits = limits;
        this.status = status;
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
