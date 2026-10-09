package com.clara.dto.interaction;

import com.clara.enums.interaction.InteractionStateEnum;
import lombok.Data;

/**
 * 交互请求：一次交互请求的完整快照（上下文 + 问题 + 状态 + 响应）。
 */
@Data
public final class InteractionRequest {

    private final InteractionContext context;
    private final Question question;
    private final InteractionStateEnum state;
    private final InteractionResponse response;
    private final String terminalReason;

    /**
     * 创建交互请求。
     *
     * @param context        交互上下文，不可为 null
     * @param question       交互问题，不可为 null
     * @param state          交互状态，不可为 null
     * @param response       交互响应，可为 null（尚未应答时）
     * @param terminalReason 终止原因，可为 null
     * @throws IllegalArgumentException context、question 或 state 为 null 时
     */
    public InteractionRequest(InteractionContext context, Question question, InteractionStateEnum state,
            InteractionResponse response, String terminalReason) {
        if (context == null || question == null || state == null) {
            throw new IllegalArgumentException("request fields are invalid");
        }
        this.context = context;
        this.question = question;
        this.state = state;
        this.response = response;
        this.terminalReason = terminalReason;
    }
}
