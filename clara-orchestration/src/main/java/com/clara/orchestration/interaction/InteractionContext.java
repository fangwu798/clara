package com.clara.orchestration.interaction;

public final class InteractionContext {
    private final String interactionId;
    private final String tenantId;
    private final String runId;
    private final String instanceId;
    private final String nodeOrTaskId;
    private final long expectedInstanceVersion;
    private final long createdAtEpochMillis;
    private final long expiresAtEpochMillis;
    public InteractionContext(String interactionId, String tenantId, String runId, String instanceId,
            String nodeOrTaskId, long expectedInstanceVersion, long createdAtEpochMillis,
            long expiresAtEpochMillis) {
        if (blank(interactionId) || blank(tenantId) || blank(runId) || blank(instanceId)
                || blank(nodeOrTaskId) || expectedInstanceVersion < 0L
                || createdAtEpochMillis < 0L || expiresAtEpochMillis <= createdAtEpochMillis) {
            throw new IllegalArgumentException("interaction context is invalid");
        }
        this.interactionId = interactionId; this.tenantId = tenantId; this.runId = runId;
        this.instanceId = instanceId; this.nodeOrTaskId = nodeOrTaskId;
        this.expectedInstanceVersion = expectedInstanceVersion;
        this.createdAtEpochMillis = createdAtEpochMillis; this.expiresAtEpochMillis = expiresAtEpochMillis;
    }
    public String getInteractionId() { return interactionId; }
    public String getTenantId() { return tenantId; }
    public String getRunId() { return runId; }
    public String getInstanceId() { return instanceId; }
    public String getNodeOrTaskId() { return nodeOrTaskId; }
    public long getExpectedInstanceVersion() { return expectedInstanceVersion; }
    public long getCreatedAtEpochMillis() { return createdAtEpochMillis; }
    public long getExpiresAtEpochMillis() { return expiresAtEpochMillis; }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
