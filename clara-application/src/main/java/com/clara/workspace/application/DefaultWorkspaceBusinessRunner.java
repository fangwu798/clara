package com.clara.workspace.application;

import com.clara.enums.CapabilityKindEnum;
import com.clara.orchestration.DefaultConversationOrchestrator;
import com.clara.orchestration.api.ConversationRequest;
import com.clara.orchestration.api.ConversationResult;
import com.clara.orchestration.api.OrchestrationStatusEnum;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link WorkspaceBusinessRunner} 的默认实现：把工作区运行 / 恢复请求落到装配好的对话编排能力。
 *
 * <p>是什么、在哪一层：application 层 workspace 业务入口的实现，把 CLI / REST / SSE 共用的业务请求
 * 映射为 {@link ConversationRequest} 并调用 {@link DefaultConversationOrchestrator#converse}，
 * 再把 {@link ConversationResult} 映射回 {@link WorkspaceRunResult}。
 *
 * <p>映射假设：以 {@code runId} 作为会话标识、以目标 / 答案作为本轮用户输入、能力种类固定为
 * {@link CapabilityKindEnum#CHAT}；模型与供应商直接取自请求（请求未携带模型时以缺省标识兜底）。
 *
 * <p>取消：底层编排为同步执行，未暴露可中断句柄，{@link #cancel} 仅做幂等空处理（详见方法说明）。
 */
public final class DefaultWorkspaceBusinessRunner implements WorkspaceBusinessRunner {

    /**
     * 缺省模型标识：请求未携带模型时的最终兜底，编排调用需要一个非空模型占位。
     */
    private static final String DEFAULT_MODEL = "default";

    private final DefaultConversationOrchestrator orchestrator;

    public DefaultWorkspaceBusinessRunner(DefaultConversationOrchestrator orchestrator) {
        if (orchestrator == null) {
            throw new IllegalArgumentException("orchestrator must not be null");
        }
        this.orchestrator = orchestrator;
    }

    @Override
    public WorkspaceRunResult run(WorkspaceRunRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        ConversationResult result = orchestrator.converse(ConversationRequest.builder()
                .userId(request.getUserId())
                .conversationId(request.getRunId())
                .provider(request.getProvider())
                .model(effectiveModel(request.getModel()))
                .capabilityKind(CapabilityKindEnum.CHAT)
                .userInput(request.getGoal())
                .stream(false)
                .build());
        return toResult(request.getRunId(), result, 1L);
    }

    @Override
    public WorkspaceRunResult resume(WorkspaceResumeRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        ConversationResult result = orchestrator.converse(ConversationRequest.builder()
                .userId(request.getUserId())
                .conversationId(request.getRunId())
                .provider(request.getProvider())
                .model(effectiveModel(request.getModel()))
                .capabilityKind(CapabilityKindEnum.CHAT)
                .userInput(answerText(request.getAnswer()))
                .stream(false)
                .build());
        return toResult(request.getRunId(), result, request.getExpectedInstanceVersion() + 1L);
    }

    /**
     * 取消当前运行实例。
     *
     * <p>限制：底层编排（{@code DefaultConversationOrchestrator.converse}）为同步执行、未对外暴露取消令牌
     * 或运行实例句柄，调用方无法从外部中断进行中的编排，因此本实现为幂等空操作（记录语义由调用方 / 上层
     * 承担）。如需真正可取消，需由编排层提供按 runId 的可中断句柄，超出本模块范围。
     */
    @Override
    public void cancel(String runId, String reason) {
        if (runId == null || runId.trim().isEmpty()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
    }

    private static WorkspaceRunResult toResult(String runId, ConversationResult result, long instanceVersion) {
        OrchestrationStatusEnum status = result.getStatus();
        WorkspaceRunStatus mapped;
        if (status == OrchestrationStatusEnum.COMPLETED) {
            mapped = WorkspaceRunStatus.SUCCEEDED;
        } else if (status == OrchestrationStatusEnum.CANCELLED) {
            mapped = WorkspaceRunStatus.CANCELLED;
        } else {
            mapped = WorkspaceRunStatus.FAILED;
        }
        Map<String, Object> outputs = new LinkedHashMap<String, Object>();
        outputs.put("content", result.getContent());
        return new WorkspaceRunResult(runId, mapped, instanceVersion, outputs, null);
    }

    private static String effectiveModel(String model) {
        return model == null || model.trim().isEmpty() ? DEFAULT_MODEL : model;
    }

    private static String answerText(Map<String, Object> answer) {
        if (answer == null || answer.isEmpty()) {
            return "";
        }
        Object text = answer.get("text");
        return text instanceof String ? (String) text : answer.toString();
    }
}
