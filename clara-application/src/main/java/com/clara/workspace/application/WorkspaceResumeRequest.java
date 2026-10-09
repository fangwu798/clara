package com.clara.workspace.application;

import com.clara.enums.ModelProviderEnum;

import java.nio.file.Path;
import java.util.*;

/**
 * 封装工作区恢复操作的输入参数。
 */
public final class WorkspaceResumeRequest {
    private final String userId, runId, interactionId, idempotencyKey;
    private final Path workspace;
    private final long expectedInstanceVersion;
    private final Map<String, Object> answer;
    private final ModelProviderEnum provider;
    private final String model;

    public WorkspaceResumeRequest(String user, String run, Path workspace, String interaction, long expected, String key, Map<String, Object> answer, ModelProviderEnum provider, String model) {
        if (blank(user) || blank(run) || workspace == null || !workspace.isAbsolute() || blank(interaction) || expected < 0 || blank(key)) {
            throw new IllegalArgumentException("workspace resume request is invalid");
        }
        userId = user;
        runId = run;
        this.workspace = workspace.normalize();
        interactionId = interaction;
        expectedInstanceVersion = expected;
        idempotencyKey = key;
        this.answer = answer == null ? Collections.<String, Object>emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(answer));
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

    public String getInteractionId() {
        return interactionId;
    }

    public long getExpectedInstanceVersion() {
        return expectedInstanceVersion;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Map<String, Object> getAnswer() {
        return answer;
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
