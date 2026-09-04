package com.clara.kernel;

public final class InvocationContext {
    private final String tenantId;
    private final String userId;
    private final String runId;
    private final String instanceId;
    private final String traceId;
    private final long deadlineEpochMillis;
    private final CancellationToken cancellationToken;

    public InvocationContext(String tenantId, String userId, String runId,
            String traceId, long deadlineEpochMillis, CancellationToken cancellationToken) {
        this(tenantId, userId, runId, null, traceId, deadlineEpochMillis, cancellationToken);
    }

    public InvocationContext(String tenantId, String userId, String runId, String instanceId,
            String traceId, long deadlineEpochMillis, CancellationToken cancellationToken) {
        if (isBlank(tenantId)) throw new IllegalArgumentException("tenantId must not be blank");
        if (isBlank(traceId)) throw new IllegalArgumentException("traceId must not be blank");
        if (cancellationToken == null) throw new IllegalArgumentException("cancellationToken must not be null");
        this.tenantId = tenantId;
        this.userId = userId;
        this.runId = runId;
        this.instanceId = instanceId;
        this.traceId = traceId;
        this.deadlineEpochMillis = deadlineEpochMillis;
        this.cancellationToken = cancellationToken;
    }
    public String getTenantId() { return tenantId; }
    public String getUserId() { return userId; }
    public String getRunId() { return runId; }
    public String getInstanceId() { return instanceId; }
    public String getTraceId() { return traceId; }
    public long getDeadlineEpochMillis() { return deadlineEpochMillis; }
    public CancellationToken getCancellationToken() { return cancellationToken; }
    public long remainingMillis(long now) {
        return Math.max(0L, deadlineEpochMillis - now);
    }
    public InvocationContext child(String childRunId, String childInstanceId, long childDeadlineEpochMillis) {
        if (childDeadlineEpochMillis > deadlineEpochMillis) {
            throw new IllegalArgumentException("child deadline must not exceed parent deadline");
        }
        return new InvocationContext(tenantId, userId, childRunId, childInstanceId,
                traceId, childDeadlineEpochMillis, cancellationToken);
    }
    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
