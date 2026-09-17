package com.clara.media.job.local;

import java.nio.file.Path;

/**
 * 定义本地媒体任务存储器的配置。
 */
public final class LocalMediaJobStoreConfig {
    private final Path workspaceRoot;
    private final String workspaceId;
    private final int maxJobs;
    private final long maxFileBytes;

    public LocalMediaJobStoreConfig(Path workspaceRoot, String workspaceId,
                                    int maxJobs, long maxFileBytes) {
        if (workspaceRoot == null) {
            throw new IllegalArgumentException("workspaceRoot must not be null");
        }
        if (workspaceId == null || workspaceId.trim().isEmpty()) {
            throw new IllegalArgumentException("workspaceId must not be blank");
        }
        if (maxJobs <= 0) {
            throw new IllegalArgumentException("maxJobs must be positive");
        }
        if (maxFileBytes <= 0L) {
            throw new IllegalArgumentException("maxFileBytes must be positive");
        }
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.workspaceId = workspaceId;
        this.maxJobs = maxJobs;
        this.maxFileBytes = maxFileBytes;
    }

    public Path getWorkspaceRoot() {
        return workspaceRoot;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public int getMaxJobs() {
        return maxJobs;
    }

    public long getMaxFileBytes() {
        return maxFileBytes;
    }
}
