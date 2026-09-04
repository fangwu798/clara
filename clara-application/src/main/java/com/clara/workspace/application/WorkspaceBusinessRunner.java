package com.clara.workspace.application;
public interface WorkspaceBusinessRunner {WorkspaceRunResult run(WorkspaceRunRequest request);WorkspaceRunResult resume(WorkspaceResumeRequest request);void cancel(String tenantId,String runId,String reason);}
