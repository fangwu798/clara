package com.clara.workspace.api;

import java.util.*;

/**
 * 提供变更的变更预览。
 */
public final class ChangePreview {
    private final String changeSetId, changeSetDigest, previewDigest;
    private final int revision;
    private final List<FilePreview> files;

    public ChangePreview(String id, int revision, String digest, String previewDigest, List<FilePreview> files) {
        this.changeSetId = id;
        this.revision = revision;
        this.changeSetDigest = digest;
        this.previewDigest = previewDigest;
        this.files = Collections.unmodifiableList(new ArrayList<FilePreview>(files));
    }

    public String getChangeSetId() {
        return changeSetId;
    }

    public int getRevision() {
        return revision;
    }

    public String getChangeSetDigest() {
        return changeSetDigest;
    }

    public String getPreviewDigest() {
        return previewDigest;
    }

    public List<FilePreview> getFiles() {
        return files;
    }

    /**
     * 提供文件的变更预览。
     */
    public static final class FilePreview {
        private final String relativePath, baseHash, resultHash;
        private final long resultBytes;

        public FilePreview(String path, String base, String result, long bytes) {
            relativePath = path;
            baseHash = base;
            resultHash = result;
            resultBytes = bytes;
        }

        public String getRelativePath() {
            return relativePath;
        }

        public String getBaseHash() {
            return baseHash;
        }

        public String getResultHash() {
            return resultHash;
        }

        public long getResultBytes() {
            return resultBytes;
        }
    }
}
