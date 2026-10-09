package com.clara.workspace.application;

/**
 * 运行工作区业务工作流。
 */
public interface WorkspaceBusinessRunner {
    WorkspaceRunResult run(WorkspaceRunRequest request);

    WorkspaceRunResult resume(WorkspaceResumeRequest request);

    void cancel(String runId, String reason);
}
