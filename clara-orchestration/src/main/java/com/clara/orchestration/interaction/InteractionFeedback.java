package com.clara.orchestration.interaction;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class InteractionFeedback {
    private final String tenantId;
    private final String runId;
    private final String interactionId;
    private final String responderId;
    private final long expectedInstanceVersion;
    private final String idempotencyKey;
    private final Map<String, Object> answer;
    public InteractionFeedback(String tenantId, String runId, String interactionId,
            String responderId, long expectedInstanceVersion, String idempotencyKey,
            Map<String, Object> answer) {
        if (blank(tenantId) || blank(runId) || blank(interactionId) || blank(responderId)
                || expectedInstanceVersion < 0L || blank(idempotencyKey)) {
            throw new IllegalArgumentException("feedback fields are invalid");
        }
        this.tenantId = tenantId; this.runId = runId; this.interactionId = interactionId;
        this.responderId = responderId; this.expectedInstanceVersion = expectedInstanceVersion;
        this.idempotencyKey = idempotencyKey;
        this.answer = answer == null ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(answer));
    }
    public String getTenantId() { return tenantId; }
    public String getRunId() { return runId; }
    public String getInteractionId() { return interactionId; }
    public String getResponderId() { return responderId; }
    public long getExpectedInstanceVersion() { return expectedInstanceVersion; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Map<String, Object> getAnswer() { return answer; }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
