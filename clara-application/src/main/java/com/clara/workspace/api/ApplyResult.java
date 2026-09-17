package com.clara.workspace.api;

import java.util.*;

/**
 * 封装应用操作的执行结果。
 */
public final class ApplyResult {
    private final String changeSetId;
    private final ApplyState state;
    private final List<FileApplyResult> files;

    public ApplyResult(String id, ApplyState state, List<FileApplyResult> files) {
        changeSetId = id;
        this.state = state;
        this.files = Collections.unmodifiableList(new ArrayList<FileApplyResult>(files));
    }

    public String getChangeSetId() {
        return changeSetId;
    }

    public ApplyState getState() {
        return state;
    }

    public List<FileApplyResult> getFiles() {
        return files;
    }
}
