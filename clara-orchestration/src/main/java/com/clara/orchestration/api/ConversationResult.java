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
    /** 本轮编排中模型返回的思维链（thinking）累积文本；无思维链时为 null。 */
    private String reasoning;
    /** 本轮生成的图片张数（仅 IMAGE 能力，平台未返回时为 null）。 */
    private Integer generatedImages;
    /** 编排终态。 */
    private OrchestrationStatusEnum status;
    /** 本次编排发生的工具往返次数。 */
    private int toolRoundTrips;
    /** 最后一次模型调用的 token 用量；无法统计时为 null。 */
    private TokenUsage usage;
    /** 失败时的结构化错误；非失败态为 null。 */
    private ClaraError error;
    /**
     * 本轮是否因等待人工输入而提前结束。
     *
     * <p>为 true 时 content 是工作流给出的确认文案，而不是模型流式输出的正文；宿主需要把它展示给用户，
     * 并在下一轮按 content 中的提示继续（恢复工作流实例）。
     */
    private boolean pausedForUserInput;
}
