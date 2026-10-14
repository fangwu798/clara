package com.clara.workspace.api;

import com.clara.workspace.application.WorkspaceResumeRequest;
import com.clara.workspace.application.WorkspaceRunRequest;
import com.clara.workspace.application.WorkspaceRunResult;

/**
 * 运行工作区业务工作流。
 */
public interface WorkspaceBusinessRunner {
    WorkspaceRunResult run(WorkspaceRunRequest request);

    WorkspaceRunResult resume(WorkspaceResumeRequest request);

    void cancel(String runId, String reason);
}
