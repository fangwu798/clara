package com.clara.workflow.planner;

import com.clara.dto.planner.TaskNode;
import com.clara.dto.planner.TaskPlan;
import com.clara.enums.planner.PlanStatusEnum;
import com.clara.enums.ErrorCategoryEnum;
import com.clara.exception.ErrorCodes;
import com.clara.utils.ObjectUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 校验并生成重规划计划，保持已完成任务幂等键 / 输出键 / 能力一致。
 */
public final class TaskPlanReplanner {

    private final PlanValidator validator;

    public TaskPlanReplanner(PlanValidator validator) {
        if (validator == null) {
            throw new IllegalArgumentException("validator must not be null");
        }
        this.validator = validator;
    }

    public TaskPlan replan(TaskPlan current, TaskPlan replacement, int priorReplans,
            Set<String> completedTaskIds) {
        if (current == null || replacement == null || priorReplans < 0 || completedTaskIds == null) {
            invalid();
        }
        if ((current.getStatus() != PlanStatusEnum.RUNNING
                && current.getStatus() != PlanStatusEnum.REPLANNING)
                || !current.getGoal().equals(replacement.getGoal())
                || replacement.getVersion() != current.getVersion() + 1
                || priorReplans >= validator.getLimits().getMaxRePlans()) {
            invalid();
        }
        Map<String, TaskNode> oldTasks = index(current);
        Map<String, TaskNode> newTasks = index(replacement);
        for (String taskId : completedTaskIds) {
            TaskNode oldTask = oldTasks.get(taskId);
            TaskNode newTask = newTasks.get(taskId);
            if (oldTask == null || newTask == null
                    || !oldTask.getOutputKey().equals(newTask.getOutputKey())
                    || !ObjectUtils.equal(oldTask.getCapability(), newTask.getCapability())) {
                invalid();
            }
        }
        return validator.validate(replacement);
    }

    private static Map<String, TaskNode> index(TaskPlan plan) {
        Map<String, TaskNode> result = new HashMap<>();
        for (TaskNode task : plan.getTasks()) {
            result.put(task.getTaskId(), task);
        }
        return result;
    }

    private static void invalid() {
        throw PlannerErrors.error(ErrorCodes.PLAN_RE_PLAN_INVALID, ErrorCategoryEnum.VALIDATION);
    }

}
