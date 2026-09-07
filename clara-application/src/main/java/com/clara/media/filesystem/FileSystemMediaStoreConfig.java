package com.clara.media.filesystem;

import java.nio.file.Path;
import com.clara.media.validation.MediaValidationPolicy;
import java.util.List;
import java.util.Map;

/**
 * 定义文件系统媒体存储器的配置。
 */
public final class FileSystemMediaStoreConfig {
    private final Path workspaceRoot;
    private final MediaValidationPolicy validationPolicy;

    public FileSystemMediaStoreConfig(Path workspaceRoot, long maxFileBytes, long maxTenantBytes,
            long retentionMillis, int maxImageWidth, int maxImageHeight, long maxDurationMs,
            Map<String, String> extensionMimeTypes, Map<String, List<String>> magicPrefixes) {
        this(workspaceRoot, new MediaValidationPolicy(maxFileBytes, maxTenantBytes, retentionMillis,
                maxImageWidth, maxImageHeight, maxDurationMs, extensionMimeTypes, magicPrefixes));
    }

    public FileSystemMediaStoreConfig(Path workspaceRoot, MediaValidationPolicy validationPolicy) {
        if (workspaceRoot == null) { throw new IllegalArgumentException("workspaceRoot must not be null"); }
        if (validationPolicy == null) { throw new IllegalArgumentException("validationPolicy must not be null"); }
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.validationPolicy = validationPolicy;
    }
    public Path getWorkspaceRoot() { return workspaceRoot; }
    public MediaValidationPolicy getValidationPolicy() { return validationPolicy; }
    public long getMaxFileBytes() { return validationPolicy.getMaxFileBytes(); }
    public long getMaxTenantBytes() { return validationPolicy.getMaxTenantBytes(); }
    public long getRetentionMillis() { return validationPolicy.getRetentionMillis(); }
    public int getMaxImageWidth() { return validationPolicy.getMaxImageWidth(); }
    public int getMaxImageHeight() { return validationPolicy.getMaxImageHeight(); }
    public long getMaxDurationMs() { return validationPolicy.getMaxDurationMs(); }
    public Map<String, String> getExtensionMimeTypes() { return validationPolicy.getExtensionMimeTypes(); }
    public Map<String, List<String>> getMagicPrefixes() { return validationPolicy.getMagicPrefixes(); }
}
