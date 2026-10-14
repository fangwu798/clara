package com.clara.workspace.application;
import com.clara.enums.workspace.WorkspaceRunStatus;

import com.clara.dto.interaction.InteractionRequest;

import java.util.*;

/**
 * 封装工作区运行操作的执行结果。
 */
public final class WorkspaceRunResult {
    private final String runId;
    private final WorkspaceRunStatus status;
    private final long instanceVersion;
    private final Map<String, Object> outputs;
    private final List<InteractionRequest> interactions;

    public WorkspaceRunResult(String run, WorkspaceRunStatus status, long version, Map<String, Object> outputs, List<InteractionRequest> interactions) {
        if (run == null || status == null || version < 0) {
            throw new IllegalArgumentException("workspace result is invalid");
        }
        runId = run;
        this.status = status;
        instanceVersion = version;
        this.outputs = outputs == null ? Collections.<String, Object>emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(outputs));
        this.interactions = interactions == null ? Collections.<InteractionRequest>emptyList() : Collections.unmodifiableList(new ArrayList<InteractionRequest>(interactions));
        if (status == WorkspaceRunStatus.WAITING_INPUT && this.interactions.isEmpty()) {
            throw new IllegalArgumentException("waiting result requires interaction");
        }
    }

    public String getRunId() {
        return runId;
    }

    public WorkspaceRunStatus getStatus() {
        return status;
    }

    public long getInstanceVersion() {
        return instanceVersion;
    }

    public Map<String, Object> getOutputs() {
        return outputs;
    }

    public List<InteractionRequest> getInteractions() {
        return interactions;
    }
}
