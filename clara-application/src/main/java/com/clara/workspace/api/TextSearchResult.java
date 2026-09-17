package com.clara.workspace.api;

import java.util.*;

/**
 * 封装文本搜索操作的执行结果。
 */
public final class TextSearchResult {
    private final List<TextMatch> matches;
    private final int scannedFiles;
    private final String queryFingerprint, snapshotFingerprint;
    private final boolean truncated;

    public TextSearchResult(List<TextMatch> matches, int scanned, String query, String snapshot, boolean truncated) {
        this.matches = Collections.unmodifiableList(new ArrayList<TextMatch>(matches));
        scannedFiles = scanned;
        queryFingerprint = query;
        snapshotFingerprint = snapshot;
        this.truncated = truncated;
    }

    public List<TextMatch> getMatches() {
        return matches;
    }

    public int getScannedFiles() {
        return scannedFiles;
    }

    public String getQueryFingerprint() {
        return queryFingerprint;
    }

    public String getSnapshotFingerprint() {
        return snapshotFingerprint;
    }

    public boolean isTruncated() {
        return truncated;
    }
}
