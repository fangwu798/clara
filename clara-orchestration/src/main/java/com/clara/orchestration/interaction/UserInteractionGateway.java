package com.clara.orchestration.interaction;

/**
 * 提供访问用户交互操作的统一入口。
 */
public interface UserInteractionGateway {
    InteractionRequest request(InteractionContext context, Question question);
}
