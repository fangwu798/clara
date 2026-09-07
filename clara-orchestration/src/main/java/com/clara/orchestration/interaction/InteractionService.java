package com.clara.orchestration.interaction;

import java.util.List;

/**
 * 提供交互相关的应用服务。
 */
public interface InteractionService {
    List<InteractionRequest> pending(String tenantId, String runId);
    InteractionRequest get(String tenantId, String runId, String interactionId);
    FeedbackResult submit(InteractionFeedback feedback);
    void cancel(String tenantId, String interactionId, String reason);
}
