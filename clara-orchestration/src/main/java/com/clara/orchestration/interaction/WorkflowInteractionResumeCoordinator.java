package com.clara.orchestration.interaction;

import com.clara.workflow.WorkflowDefinition;
import com.clara.workflow.WorkflowEngine;
import com.clara.workflow.WorkflowResult;
import java.util.List;
import java.util.Map;

/**
 * 将已验证的交互反馈转换为工作流输入，并协调工作流从检查点恢复。
 *
 * <p>常规用法：作为 {@link InteractionResumeCoordinator} 注入交互服务，由交互提交过程调用
 * {@link #resumeAndRecord}；交互取消时通过 {@link #interactionCancelled} 同步取消工作流实例。
 *
 * <p>注意：恢复前会重新解析工作流定义，并使用交互记录的检查点版本进行并发校验；定义版本变化、
 * 重复反馈或检查点已推进都会导致恢复失败，不应绕过交互服务直接调用本类。
 */
public final class WorkflowInteractionResumeCoordinator implements InteractionResumeCoordinator {
    private final WorkflowEngine engine;
    private final WorkflowDefinitionResolver definitions;
    public WorkflowInteractionResumeCoordinator(WorkflowEngine engine,
            WorkflowDefinitionResolver definitions) {
        if (engine == null || definitions == null) { throw new IllegalArgumentException("engine and definitions are required"); }
        this.engine = engine; this.definitions = definitions;
    }
    public ResumeOutcome resumeAndRecord(InteractionRequest trigger,
            List<InteractionResponse> responses, Map<String, Object> scopeUpdates) {
        WorkflowDefinition definition = definitions.resolve(trigger.getInstanceId());
        if (definition == null) { throw new IllegalArgumentException("workflow definition was not resolved"); }
        WorkflowResult result = engine.resume(definition, trigger.getInstanceId(), scopeUpdates,
                trigger.getExpectedInstanceVersion());
        return new ResumeOutcome(result.getStatus().name(), result.getCheckpointVersion());
    }
    public void interactionCancelled(InteractionRequest request, String reason) {
        engine.cancel(request.getInstanceId());
    }
}
