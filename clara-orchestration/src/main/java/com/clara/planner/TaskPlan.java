package com.clara.planner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表示。
 */
public final class TaskPlan {
    private static final int SCHEMA_VERSION = 1;
    private final String planId;
    private final String goal;
    private final int planVersion;
    private final List<TaskNode> tasks;
    private final Map<String, String> constraints;
    private final PlanStatus status;

    public TaskPlan(String planId, String goal, int planVersion, List<TaskNode> tasks,
            Map<String, String> constraints, PlanStatus status) {
        if (blank(planId) || blank(goal) || planVersion <= 0 || tasks == null || tasks.isEmpty()
                || tasks.contains(null) || status == null) { throw new IllegalArgumentException("plan fields are invalid"); }
        this.planId = planId; this.goal = goal; this.planVersion = planVersion;
        this.tasks = Collections.unmodifiableList(new ArrayList<TaskNode>(tasks));
        this.constraints = constraints == null ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(constraints));
        this.status = status;
    }
    public int getSchemaVersion() { return SCHEMA_VERSION; }
    public String getPlanId() { return planId; }
    public String getGoal() { return goal; }
    public int getPlanVersion() { return planVersion; }
    public List<TaskNode> getTasks() { return tasks; }
    public Map<String, String> getConstraints() { return constraints; }
    public PlanStatus getStatus() { return status; }
    public TaskPlan withStatus(PlanStatus value) {
        return new TaskPlan(planId, goal, planVersion, tasks, constraints, value);
    }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
