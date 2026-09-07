package com.clara.deployment.local;

import com.clara.media.filesystem.FileSystemMediaStoreConfig;
import com.clara.media.job.callback.MediaCallbackSecurityPolicy;
import com.clara.media.job.local.LocalMediaJobStoreConfig;
import com.clara.media.validation.MediaValidationPolicy;
import com.clara.workspace.api.WorkspacePolicy;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 定义 Windows 单机部署的存储与保留基线。
 */
public final class WindowsSingleHostStoragePolicy {
    public static final long MIB = 1024L * 1024L;
    public static final long GIB = 1024L * MIB;
    public static final long HOUR_MILLIS = 60L * 60L * 1000L;
    public static final long DAY_MILLIS = 24L * HOUR_MILLIS;

    public static final long MEDIA_MAX_FILE_BYTES = 64L * MIB;
    public static final long MEDIA_MAX_TENANT_BYTES = 2L * GIB;
    public static final long MEDIA_RETENTION_MILLIS = 30L * DAY_MILLIS;
    public static final int MEDIA_MAX_IMAGE_WIDTH = 8192;
    public static final int MEDIA_MAX_IMAGE_HEIGHT = 8192;
    public static final long MEDIA_MAX_DURATION_MILLIS = 30L * 60L * 1000L;

    public static final int WORKSPACE_MAX_FILES = 20000;
    public static final long WORKSPACE_MAX_FILE_BYTES = 32L * MIB;
    public static final long WORKSPACE_MAX_TOTAL_BYTES = 1L * GIB;
    public static final int MEDIA_JOB_MAX_ENTRIES = 10000;
    public static final long MEDIA_JOB_MAX_STATE_BYTES = 16L * MIB;

    public static final long CONVERSATION_RETENTION_MILLIS = 90L * DAY_MILLIS;
    public static final long TERMINAL_JOB_RETENTION_MILLIS = 90L * DAY_MILLIS;
    public static final long AUDIT_RETENTION_MILLIS = 180L * DAY_MILLIS;
    public static final long STAGING_RETENTION_MILLIS = DAY_MILLIS;
    public static final long BACKUP_RETENTION_MILLIS = 28L * DAY_MILLIS;
    public static final long BACKUP_INTERVAL_MILLIS = DAY_MILLIS;
    public static final long BACKUP_RPO_MILLIS = DAY_MILLIS;
    public static final long BACKUP_RTO_MILLIS = 4L * HOUR_MILLIS;
    public static final long RESTORE_DRILL_INTERVAL_MILLIS = 90L * DAY_MILLIS;

    private final Path workspaceRoot;
    private final String workspaceId;
    private final MediaValidationPolicy mediaValidationPolicy;

    public WindowsSingleHostStoragePolicy(Path workspaceRoot, String workspaceId) {
        if (workspaceRoot == null) { throw new IllegalArgumentException("workspaceRoot must not be null"); }
        if (workspaceId == null || workspaceId.trim().isEmpty()) {
            throw new IllegalArgumentException("workspaceId must not be blank");
        }
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.workspaceId = workspaceId;
        this.mediaValidationPolicy = new MediaValidationPolicy(MEDIA_MAX_FILE_BYTES,
                MEDIA_MAX_TENANT_BYTES, MEDIA_RETENTION_MILLIS, MEDIA_MAX_IMAGE_WIDTH,
                MEDIA_MAX_IMAGE_HEIGHT, MEDIA_MAX_DURATION_MILLIS, mediaTypes(), magicPrefixes(),
                true, false);
    }

    public FileSystemMediaStoreConfig mediaStoreConfig() {
        return new FileSystemMediaStoreConfig(workspaceRoot, mediaValidationPolicy);
    }

    public LocalMediaJobStoreConfig mediaJobStoreConfig() {
        return new LocalMediaJobStoreConfig(workspaceRoot, workspaceId,
                MEDIA_JOB_MAX_ENTRIES, MEDIA_JOB_MAX_STATE_BYTES);
    }

    public MediaCallbackSecurityPolicy mediaCallbackSecurityPolicy() {
        return new MediaCallbackSecurityPolicy(5L * 60L * 1000L, (int) MIB, 256, 128, 100000);
    }

    public WorkspacePolicy workspacePolicy() {
        return new WorkspacePolicy(workspaceExtensions(), ignoredNames(), sensitiveNames(),
                WORKSPACE_MAX_FILES, WORKSPACE_MAX_FILE_BYTES, WORKSPACE_MAX_TOTAL_BYTES, false);
    }

    public MediaValidationPolicy getMediaValidationPolicy() { return mediaValidationPolicy; }
    public long getConversationRetentionMillis() { return CONVERSATION_RETENTION_MILLIS; }
    public long getTerminalJobRetentionMillis() { return TERMINAL_JOB_RETENTION_MILLIS; }
    public long getAuditRetentionMillis() { return AUDIT_RETENTION_MILLIS; }
    public long getStagingRetentionMillis() { return STAGING_RETENTION_MILLIS; }
    public long getBackupRetentionMillis() { return BACKUP_RETENTION_MILLIS; }
    public long getBackupIntervalMillis() { return BACKUP_INTERVAL_MILLIS; }
    public long getBackupRpoMillis() { return BACKUP_RPO_MILLIS; }
    public long getBackupRtoMillis() { return BACKUP_RTO_MILLIS; }
    public long getRestoreDrillIntervalMillis() { return RESTORE_DRILL_INTERVAL_MILLIS; }
    public boolean isEncryptedVolumeRequired() { return true; }
    public boolean isWindowsCredentialStoreRequired() { return true; }
    public boolean isExternalMediaAllowedWithoutSafetyScanner() { return false; }

    private static Map<String, String> mediaTypes() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put(".png", "image/png");
        values.put(".jpg", "image/jpeg");
        values.put(".jpeg", "image/jpeg");
        values.put(".gif", "image/gif");
        values.put(".wav", "audio/wav");
        return values;
    }

    private static Map<String, List<String>> magicPrefixes() {
        Map<String, List<String>> values = new LinkedHashMap<String, List<String>>();
        values.put("image/png", Collections.singletonList("89504E470D0A1A0A"));
        values.put("image/jpeg", Collections.singletonList("FFD8FF"));
        values.put("image/gif", Arrays.asList("474946383761", "474946383961"));
        values.put("audio/wav", Collections.singletonList("52494646"));
        return values;
    }

    private static Set<String> workspaceExtensions() {
        return set(".java", ".xml", ".md", ".txt", ".json", ".yaml", ".yml",
                ".properties", ".csv", ".docx", ".xlsx", ".pdf");
    }

    private static Set<String> ignoredNames() {
        return set(".git", ".idea", ".clara", "target", "node_modules");
    }

    private static Set<String> sensitiveNames() {
        return set(".env", "id_rsa", "id_ed25519", "credentials", "secrets.yml", "secrets.yaml");
    }

    private static Set<String> set(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(values)));
    }
}
