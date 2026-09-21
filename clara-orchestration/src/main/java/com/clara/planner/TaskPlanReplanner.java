package com.clara.planner;

import com.clara.kernel.exception.ErrorCategory;
import com.clara.kernel.exception.ErrorCodes;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 表示。
 */
public final class TaskPlanReplanner {
    private final PlanValidator validator;
    public TaskPlanReplanner(PlanValidator validator) {
        if (validator == null) { throw new IllegalArgumentException("validator must not be null"); }
        this.validator = validator;
    }
    public TaskPlan replan(TaskPlan current, TaskPlan replacement, int priorReplans,
            Set<String> completedTaskIds) {
        if (current == null || replacement == null || priorReplans < 0 || completedTaskIds == null) invalid();
        if ((current.getStatus() != PlanStatus.RUNNING && current.getStatus() != PlanStatus.REPLANNING)
                || !current.getPlanId().equals(replacement.getPlanId())
                || replacement.getPlanVersion() != current.getPlanVersion() + 1
                || priorReplans >= validator.getLimits().getMaxReplans()) invalid();
        Map<String, TaskNode> oldTasks = index(current);
        Map<String, TaskNode> newTasks = index(replacement);
        for (String taskId : completedTaskIds) {
            TaskNode oldTask = oldTasks.get(taskId); TaskNode newTask = newTasks.get(taskId);
            if (oldTask == null || newTask == null
                    || !oldTask.getIdempotencyKey().equals(newTask.getIdempotencyKey())
                    || !oldTask.getOutputKey().equals(newTask.getOutputKey())
                    || !equal(oldTask.getCapability(), newTask.getCapability())) invalid();
        }
        return validator.validate(replacement);
    }
    private static Map<String, TaskNode> index(TaskPlan plan) {
        Map<String, TaskNode> result = new HashMap<String, TaskNode>();
        for (TaskNode task : plan.getTasks()) result.put(task.getTaskId(), task); return result;
    }
    private static void invalid() {
        throw PlannerErrors.error(ErrorCodes.PLAN_REPLAN_INVALID, ErrorCategory.VALIDATION);
    }
    private static boolean equal(Object one, Object two) { return one == null ? two == null : one.equals(two); }
}
