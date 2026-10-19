package com.clara.dto.interaction;

import com.clara.utils.StringUtils;
import lombok.Data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 交互反馈：用户提交的交互反馈（答案、响应者、版本与幂等键）。
 *
 * <p>无租户：不含 {@code tenantId}。答案在构造时做防御性拷贝并转为不可变。
 */
@Data
public final class InteractionFeedback {

    private final String runId;
    private final String interactionId;
    private final String responderId;
    private final long expectedInstanceVersion;
    private final String idempotencyKey;
    private final Map<String, Object> answer;

    /**
     * 创建交互反馈。
     *
     * @param runId                   运行标识，不可为空白
     * @param interactionId           交互标识，不可为空白
     * @param responderId             响应者标识，不可为空白
     * @param expectedInstanceVersion 期望实例版本，不可为负
     * @param idempotencyKey          幂等键，不可为空白
     * @param answer                  用户答案，可为 null
     * @throws IllegalArgumentException 任意字段不合法时
     */
    public InteractionFeedback(String runId, String interactionId, String responderId, long expectedInstanceVersion, String idempotencyKey, Map<String, Object> answer) {
        if (StringUtils.isBlank(runId) || StringUtils.isBlank(interactionId) || StringUtils.isBlank(responderId) || expectedInstanceVersion < 0L || StringUtils.isBlank(idempotencyKey)) {
            throw new IllegalArgumentException("feedback fields are invalid");
        }
        this.runId = runId;
        this.interactionId = interactionId;
        this.responderId = responderId;
        this.expectedInstanceVersion = expectedInstanceVersion;
        this.idempotencyKey = idempotencyKey;
        this.answer = answer == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(answer));
    }

}
