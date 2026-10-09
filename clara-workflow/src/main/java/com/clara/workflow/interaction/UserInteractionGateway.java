package com.clara.workflow.interaction;

import com.clara.core.interaction.IUserInteractionGateway;
import com.clara.dto.interaction.InteractionContext;
import com.clara.dto.interaction.InteractionRequest;
import com.clara.dto.interaction.Question;

/**
 * 向用户发起交互请求的统一入口实现。
 */
public final class UserInteractionGateway implements IUserInteractionGateway {

    private final InMemoryInteractionService service;

    public UserInteractionGateway(InMemoryInteractionService service) {
        if (service == null) {
            throw new IllegalArgumentException("service must not be null");
        }
        this.service = service;
    }

    @Override
    public InteractionRequest request(InteractionContext context, Question question) {
        return service.request(context, question);
    }
}
