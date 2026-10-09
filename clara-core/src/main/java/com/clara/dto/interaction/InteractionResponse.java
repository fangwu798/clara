package com.clara.dto.interaction;

import lombok.Data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 交互响应：一次交互的响应数据（答案、响应者与幂等键）。
 */
@Data
public final class InteractionResponse {

    private final String interactionId;
    private final Map<String, Object> answer;
    private final String responderId;
    private final String idempotencyKey;
    private final long submittedAtEpochMillis;

    /**
     * 创建交互响应。
     *
     * @param interactionId          交互标识，不可为空白
     * @param answer                 用户答案，可为 null
     * @param responderId            响应者标识，不可为空白
     * @param idempotencyKey         幂等键，不可为空白
     * @param submittedAtEpochMillis 提交时间（Unix epoch 毫秒），不可为负
     * @throws IllegalArgumentException 任一字段不合法时
     */
    public InteractionResponse(String interactionId, Map<String, Object> answer, String responderId,
            String idempotencyKey, long submittedAtEpochMillis) {
        if (blank(interactionId) || blank(responderId) || blank(idempotencyKey)
                || submittedAtEpochMillis < 0L) {
            throw new IllegalArgumentException("response fields are invalid");
        }
        this.interactionId = interactionId;
        this.answer = answer == null ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(answer));
        this.responderId = responderId;
        this.idempotencyKey = idempotencyKey;
        this.submittedAtEpochMillis = submittedAtEpochMillis;
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
