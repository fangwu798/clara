package com.clara.adapter.rest;

import com.clara.exception.ClaraException;
import com.clara.workspace.application.*;

/**
 * 对外暴露工作区运行的控制接口。
 */
public final class WorkspaceRunController {
    private final WorkspaceBusinessRunner runner;

    public WorkspaceRunController(WorkspaceBusinessRunner runner) {
        if (runner == null) {
            throw new IllegalArgumentException();
        }
        this.runner = runner;
    }

    public RestResponse<WorkspaceRunResult> run(WorkspaceRunRequest request) {
        try {
            WorkspaceRunResult value = runner.run(request);
            return RestResponse.ok(value.getStatus() == WorkspaceRunStatus.WAITING_INPUT ? 202 : 200, value);
        } catch (ClaraException e) {
            return RestResponse.error(RestErrors.status(e.getError()), e.getError());
        }
    }

    public RestResponse<WorkspaceRunResult> resume(WorkspaceResumeRequest request) {
        try {
            WorkspaceRunResult value = runner.resume(request);
            return RestResponse.ok(value.getStatus() == WorkspaceRunStatus.WAITING_INPUT ? 202 : 200, value);
        } catch (ClaraException e) {
            return RestResponse.error(RestErrors.status(e.getError()), e.getError());
        }
    }

    public RestResponse<Void> cancel(String run, String reason) {
        try {
            runner.cancel(run, reason);
            return RestResponse.ok(204, null);
        } catch (ClaraException e) {
            return RestResponse.error(RestErrors.status(e.getError()), e.getError());
        }
    }
}
