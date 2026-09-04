package com.clara.orchestration.interaction;

public interface UserInteractionGateway {
    InteractionRequest request(InteractionContext context, Question question);
}
