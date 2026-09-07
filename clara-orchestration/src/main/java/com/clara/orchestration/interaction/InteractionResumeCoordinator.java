package com.clara.orchestration.interaction;

import java.util.List;
import java.util.Map;

/**
 * 协调交互恢复流程。
 */
public interface InteractionResumeCoordinator {
    ResumeOutcome resumeAndRecord(InteractionRequest trigger, List<InteractionResponse> responses,
            Map<String, Object> scopeUpdates);
    void interactionCancelled(InteractionRequest request, String reason);
}
