package com.clara.workspace.application;
import com.clara.enums.workspace.WorkspaceAccessMode;

import com.clara.enums.ModelProviderEnum;

import java.nio.file.Path;
import java.util.*;

/**
 * 封装工作区运行操作的输入参数。
 */
public final class WorkspaceRunRequest {
    private final String userId, runId, goal, skillId;
    private final Path workspace;
    private final WorkspaceAccessMode accessMode;
    private final boolean approveChanges;
    private final Map<String, Object> inputs;
    private final ModelProviderEnum provider;
    private final String model;

    public WorkspaceRunRequest(String user, String run, Path workspace, String goal, String skill, WorkspaceAccessMode mode, boolean approve, Map<String, Object> inputs, ModelProviderEnum provider, String model) {
        if (blank(user) || blank(run) || workspace == null || !workspace.isAbsolute() || mode == null) {
            throw new IllegalArgumentException("workspace run request is invalid");
        }
        userId = user;
        runId = run;
        this.workspace = workspace.normalize();
        this.goal = goal;
        skillId = skill;
        accessMode = mode;
        approveChanges = approve;
        this.inputs = inputs == null ? Collections.<String, Object>emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(inputs));
        this.provider = provider;
        this.model = model;
    }

    public String getUserId() {
        return userId;
    }

    public String getRunId() {
        return runId;
    }

    public Path getWorkspace() {
        return workspace;
    }

    public String getGoal() {
        return goal;
    }

    public String getSkillId() {
        return skillId;
    }

    public WorkspaceAccessMode getAccessMode() {
        return accessMode;
    }

    public boolean isApproveChanges() {
        return approveChanges;
    }

    public Map<String, Object> getInputs() {
        return inputs;
    }

    public ModelProviderEnum getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    private static boolean blank(String v) {
        return v == null || v.trim().isEmpty();
    }
}
