package com.clara.planner;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TaskExecutionContext {
    private final String planId;
    private final int planVersion;
    private final String taskId;
    private final int attempt;
    private final String idempotencyKey;
    private final Map<String, Object> values;

    TaskExecutionContext(String planId, int planVersion, String taskId, int attempt,
            String idempotencyKey, Map<String, Object> values) {
        this.planId = planId; this.planVersion = planVersion; this.taskId = taskId;
        this.attempt = attempt; this.idempotencyKey = idempotencyKey;
        this.values = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(values));
    }
    public String getPlanId() { return planId; }
    public int getPlanVersion() { return planVersion; }
    public String getTaskId() { return taskId; }
    public int getAttempt() { return attempt; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Map<String, Object> getValues() { return values; }
    public Object get(String key) { return values.get(key); }
}
