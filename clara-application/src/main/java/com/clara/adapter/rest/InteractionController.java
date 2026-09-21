package com.clara.adapter.rest;

import com.clara.kernel.exception.ClaraException;
import com.clara.orchestration.interaction.*;

import java.util.List;

/**
 * 对外暴露交互的控制接口。
 */
public final class InteractionController {
    private final InteractionService service;

    public InteractionController(InteractionService service) {
        if (service == null) {
            throw new IllegalArgumentException();
        }
        this.service = service;
    }

    public RestResponse<List<InteractionRequest>> pending(String tenant, String run) {
        try {
            return RestResponse.ok(200, service.pending(tenant, run));
        } catch (ClaraException e) {
            return RestResponse.error(RestErrors.status(e.getError()), e.getError());
        }
    }

    public RestResponse<InteractionRequest> get(String tenant, String run, String interaction) {
        try {
            return RestResponse.ok(200, service.get(tenant, run, interaction));
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

    public RestResponse<Void> cancel(String tenant, String interaction, String reason) {
        try {
            service.cancel(tenant, interaction, reason);
            return RestResponse.ok(204, null);
        } catch (ClaraException e) {
            return RestResponse.error(RestErrors.status(e.getError()), e.getError());
        }
    }
}
