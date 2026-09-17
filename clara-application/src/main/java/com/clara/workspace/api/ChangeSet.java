package com.clara.workspace.api;

import java.util.*;

/**
 * 表示。
 */
public final class ChangeSet {
    private final String changeSetId, runId, digest, previewDigest;
    private final int revision;
    private final List<FileChange> changes;

    public ChangeSet(String id, String runId, int revision, String digest, String previewDigest, List<FileChange> changes) {
        if (blank(id) || blank(runId) || revision <= 0 || blank(digest) || blank(previewDigest) || changes == null || changes.isEmpty()) {
            throw new IllegalArgumentException("change set is invalid");
        }
        changeSetId = id;
        this.runId = runId;
        this.revision = revision;
        this.digest = digest;
        this.previewDigest = previewDigest;
        this.changes = Collections.unmodifiableList(new ArrayList<FileChange>(changes));
    }

    public String getChangeSetId() {
        return changeSetId;
    }

    public String getRunId() {
        return runId;
    }

    public int getRevision() {
        return revision;
    }

    public String getDigest() {
        return digest;
    }

    public String getPreviewDigest() {
        return previewDigest;
    }

    public List<FileChange> getChanges() {
        return changes;
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
