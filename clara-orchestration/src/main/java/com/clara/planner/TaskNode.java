package com.clara.planner;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 表示。
 */
public final class TaskNode {
    private final String taskId;
    private final String description;
    private final Set<String> dependencies;
    private final String capability;
    private final String outputKey;
    private final RetryPolicy retryPolicy;
    private final long timeoutMillis;
    private final BudgetAmount maximumBudget;
    private final String idempotencyKey;
    private final TaskHandler handler;

    private TaskNode(Builder builder) {
        if (blank(builder.taskId) || blank(builder.description) || blank(builder.outputKey)
                || blank(builder.idempotencyKey) || builder.handler == null
                || builder.timeoutMillis <= 0L || builder.retryPolicy == null
                || builder.maximumBudget == null) {
            throw new IllegalArgumentException("task fields are invalid");
        }
        this.taskId = builder.taskId; this.description = builder.description;
        this.dependencies = Collections.unmodifiableSet(new LinkedHashSet<String>(builder.dependencies));
        this.capability = builder.capability; this.outputKey = builder.outputKey;
        this.retryPolicy = builder.retryPolicy; this.timeoutMillis = builder.timeoutMillis;
        this.maximumBudget = builder.maximumBudget; this.idempotencyKey = builder.idempotencyKey;
        this.handler = builder.handler;
    }
    public static Builder builder() { return new Builder(); }
    public String getTaskId() { return taskId; }
    public String getDescription() { return description; }
    public Set<String> getDependencies() { return dependencies; }
    public String getCapability() { return capability; }
    public String getOutputKey() { return outputKey; }
    public RetryPolicy getRetryPolicy() { return retryPolicy; }
    public long getTimeoutMillis() { return timeoutMillis; }
    public BudgetAmount getMaximumBudget() { return maximumBudget; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public TaskHandler getHandler() { return handler; }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }

    /**
     * 表示。
     */
    public static final class Builder {
        private String taskId;
        private String description;
        private Set<String> dependencies = Collections.emptySet();
        private String capability;
        private String outputKey;
        private RetryPolicy retryPolicy = RetryPolicy.NONE;
        private long timeoutMillis = 30000L;
        private BudgetAmount maximumBudget = BudgetAmount.ZERO;
        private String idempotencyKey;
        private TaskHandler handler;
        public Builder taskId(String value) { taskId = value; return this; }
        public Builder description(String value) { description = value; return this; }
        public Builder dependencies(Set<String> value) {
            dependencies = value == null ? Collections.<String>emptySet() : value; return this;
        }
        public Builder capability(String value) { capability = value; return this; }
        public Builder outputKey(String value) { outputKey = value; return this; }
        public Builder retryPolicy(RetryPolicy value) { retryPolicy = value; return this; }
        public Builder timeoutMillis(long value) { timeoutMillis = value; return this; }
        public Builder maximumBudget(BudgetAmount value) { maximumBudget = value; return this; }
        public Builder idempotencyKey(String value) { idempotencyKey = value; return this; }
        public Builder handler(TaskHandler value) { handler = value; return this; }
        public TaskNode build() { return new TaskNode(this); }
    }
}
