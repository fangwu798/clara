package com.clara.orchestration.interaction;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 封装交互操作的响应数据。
 */
public final class InteractionResponse {
    private final String interactionId;
    private final Map<String, Object> answer;
    private final String responderId;
    private final String idempotencyKey;
    private final long submittedAtEpochMillis;
    public InteractionResponse(String interactionId, Map<String, Object> answer, String responderId,
            String idempotencyKey, long submittedAtEpochMillis) {
        if (blank(interactionId) || blank(responderId) || blank(idempotencyKey)
                || submittedAtEpochMillis < 0L) { throw new IllegalArgumentException("response fields are invalid"); }
        this.interactionId = interactionId;
        this.answer = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(answer));
        this.responderId = responderId; this.idempotencyKey = idempotencyKey;
        this.submittedAtEpochMillis = submittedAtEpochMillis;
    }
    public String getInteractionId() { return interactionId; }
    public Map<String, Object> getAnswer() { return answer; }
    public String getResponderId() { return responderId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public long getSubmittedAtEpochMillis() { return submittedAtEpochMillis; }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
