package com.clara.core.interaction;

import com.clara.dto.interaction.FeedbackResult;
import com.clara.dto.interaction.InteractionFeedback;
import com.clara.dto.interaction.InteractionRequest;

import java.util.List;

/**
 * 交互应用服务入口：列出待处理、获取、提交反馈与取消。
 *
 * <p>core 定义的契约接口，由 clara-workflow 实现；无租户，交互仅按运行（run）与交互标识定位。
 */
public interface IInteractionService {

    /**
     * 列出一次运行中的全部待处理交互。
     *
     * @param runId 运行标识，不可为 null
     * @return 待处理交互列表，非 null
     */
    List<InteractionRequest> pending(String runId);

    /**
     * 按运行与交互标识获取一次交互。
     *
     * @param runId         运行标识，不可为 null
     * @param interactionId 交互标识，不可为 null
     * @return 交互请求，非 null
     */
    InteractionRequest get(String runId, String interactionId);

    /**
     * 提交用户反馈。
     *
     * @param feedback 交互反馈，不可为 null
     * @return 反馈执行结果，非 null
     */
    FeedbackResult submit(InteractionFeedback feedback);

    /**
     * 取消一次交互。
     *
     * @param interactionId 交互标识，不可为 null
     * @param reason        取消原因，不可为 null
     */
    void cancel(String interactionId, String reason);
}
