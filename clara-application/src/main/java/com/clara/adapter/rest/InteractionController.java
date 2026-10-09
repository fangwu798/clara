package com.clara.adapter.rest;

import com.clara.core.interaction.IInteractionService;
import com.clara.dto.interaction.FeedbackResult;
import com.clara.dto.interaction.InteractionFeedback;
import com.clara.dto.interaction.InteractionRequest;
import com.clara.exception.ClaraException;

import java.util.List;

/**
 * 对外暴露交互的控制接口。
 */
public final class InteractionController {
    private final IInteractionService service;

    public InteractionController(IInteractionService service) {
        if (service == null) {
            throw new IllegalArgumentException();
        }
        this.service = service;
    }

    public RestResponse<List<InteractionRequest>> pending(String run) {
        try {
            return RestResponse.ok(200, service.pending(run));
        } catch (ClaraException e) {
            return RestResponse.error(RestErrors.status(e.getError()), e.getError());
        }
    }

    public RestResponse<InteractionRequest> get(String run, String interaction) {
        try {
            return RestResponse.ok(200, service.get(run, interaction));
        } catch (ClaraException e) {
            return RestResponse.error(RestErrors.status(e.getError()), e.getError());
        }
    }

    public RestResponse<FeedbackResult> submit(InteractionFeedback feedback) {
        try {
            return RestResponse.ok(200, service.submit(feedback));
        } catch (ClaraException e) {
            return RestResponse.error(RestErrors.status(e.getError()), e.getError());
        }
    }

    public RestResponse<Void> cancel(String interaction, String reason) {
        try {
            service.cancel(interaction, reason);
            return RestResponse.ok(204, null);
        } catch (ClaraException e) {
            return RestResponse.error(RestErrors.status(e.getError()), e.getError());
        }
    }
}
