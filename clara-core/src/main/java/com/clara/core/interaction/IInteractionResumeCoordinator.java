package com.clara.core.interaction;

import com.clara.dto.interaction.InteractionRequest;
import com.clara.dto.interaction.InteractionResponse;
import com.clara.dto.interaction.ResumeOutcome;

import java.util.List;
import java.util.Map;

/**
 * 交互恢复协调接口：协调交互恢复流程（恢复并记录，或标记取消）。
 *
 * <p>core 定义的契约接口，由 clara-workflow 实现；反馈提交后据此恢复工作流 / 任务实例。
 */
public interface IInteractionResumeCoordinator {

    /**
     * 用反馈恢复实例并记录处理结果。
     *
     * @param trigger      触发恢复的交互请求，不可为 null
     * @param responses    用户提交的响应列表，不可为 null
     * @param scopeUpdates 作用域更新值，可为 null
     * @return 恢复处理结果，非 null
     */
    ResumeOutcome resumeAndRecord(InteractionRequest trigger, List<InteractionResponse> responses,
            Map<String, Object> scopeUpdates);

    /**
     * 交互被取消时的处理。
     *
     * @param request 被取消的交互请求，不可为 null
     * @param reason  取消原因，不可为 null
     */
    void interactionCancelled(InteractionRequest request, String reason);
}
