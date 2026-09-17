package com.clara.workspace.api;

/**
 * 表示。
 */
public final class TextMatch {
    private final String matchId, relativePath, fileHash, nodeId, preview;
    private final int start, end, line, column, absoluteStart, absoluteEnd;

    public TextMatch(String id, String path, String hash, String node, int start, int end, int line, int column, String preview, int absoluteStart, int absoluteEnd) {
        matchId = id;
        relativePath = path;
        fileHash = hash;
        nodeId = node;
        this.start = start;
        this.end = end;
        this.line = line;
        this.column = column;
        this.preview = preview;
        this.absoluteStart = absoluteStart;
        this.absoluteEnd = absoluteEnd;
    }

    public String getMatchId() {
        return matchId;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public String getFileHash() {
        return fileHash;
    }

    public String getNodeId() {
        return nodeId;
    }

    public int getStart() {
        return start;
    }

    public int getEnd() {
        return end;
    }

    public int getLine() {
        return line;
    }

    public int getColumn() {
        return column;
    }

    public String getPreview() {
        return preview;
    }

    public int getAbsoluteStart() {
        return absoluteStart;
    }

    public int getAbsoluteEnd() {
        return absoluteEnd;
    }
}
