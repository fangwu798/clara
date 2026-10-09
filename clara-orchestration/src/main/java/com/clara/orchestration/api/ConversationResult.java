package com.clara.orchestration.api;

import com.clara.exception.ClaraError;
import com.clara.kernel.message.delta.TokenUsage;
import lombok.Builder;
import lombok.Data;

/**
 * 一次对话的结果：编排入口的返回载体，供宿主展示与记录。
 */
@Data
@Builder
public final class ConversationResult {

    /** 会话标识。 */
    private String conversationId;
    /** 最终回复内容（对话为文本，图片等能力为对应产出）。 */
    private String content;
    /** 编排终态。 */
    private OrchestrationStatusEnum status;
    /** 本次编排发生的工具往返次数。 */
    private int toolRoundTrips;
    /** 最后一次模型调用的 token 用量；无法统计时为 null。 */
    private TokenUsage usage;
    /** 失败时的结构化错误；非失败态为 null。 */
    private ClaraError error;
}
