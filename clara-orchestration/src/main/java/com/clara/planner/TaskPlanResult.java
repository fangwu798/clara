package com.clara.planner;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TaskPlanResult {
    private final TaskPlan plan;
    private final PlanStatus status;
    private final Map<String, TaskNodeStatus> taskStatuses;
    private final Map<String, Object> outputs;
    private final int steps;
    TaskPlanResult(TaskPlan plan, PlanStatus status, Map<String, TaskNodeStatus> taskStatuses,
            Map<String, Object> outputs, int steps) {
        this.plan = plan; this.status = status;
        this.taskStatuses = Collections.unmodifiableMap(
                new LinkedHashMap<String, TaskNodeStatus>(taskStatuses));
        this.outputs = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(outputs));
        this.steps = steps;
    }
    public TaskPlan getPlan() { return plan; }
    public PlanStatus getStatus() { return status; }
    public Map<String, TaskNodeStatus> getTaskStatuses() { return taskStatuses; }
    public Map<String, Object> getOutputs() { return outputs; }
    public int getSteps() { return steps; }
}
