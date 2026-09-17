package com.clara.deployment.local;

import com.clara.media.validation.MediaValidationPolicy;

import java.nio.file.Path;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

public class WindowsSingleHostStoragePolicyTest {
    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void exposesFrozenFailClosedSingleHostLimits() throws Exception {
        Path workspace = folder.newFolder("workspace").toPath();
        WindowsSingleHostStoragePolicy policy =
                new WindowsSingleHostStoragePolicy(workspace, "workspace-1");
        MediaValidationPolicy media = policy.getMediaValidationPolicy();

        assertEquals(64L * WindowsSingleHostStoragePolicy.MIB, media.getMaxFileBytes());
        assertEquals(2L * WindowsSingleHostStoragePolicy.GIB, media.getMaxTenantBytes());
        assertEquals(30L * WindowsSingleHostStoragePolicy.DAY_MILLIS, media.getRetentionMillis());
        assertEquals(8192, media.getMaxImageWidth());
        assertEquals(30L * 60L * 1000L, media.getMaxDurationMs());
        assertTrue(media.isVerifiedMetadataRequired());
        assertFalse(media.isContentSafetyRequired());
        assertFalse(policy.isExternalMediaAllowedWithoutSafetyScanner());
        assertTrue(policy.isEncryptedVolumeRequired());
        assertTrue(policy.isWindowsCredentialStoreRequired());
    }

    @Test
    public void createsBoundedComponentConfigurations() throws Exception {
        Path workspace = folder.newFolder("configs").toPath();
        WindowsSingleHostStoragePolicy policy =
                new WindowsSingleHostStoragePolicy(workspace, "workspace-2");

        assertEquals(20000, policy.workspacePolicy().getMaxFiles());
        assertEquals(32L * WindowsSingleHostStoragePolicy.MIB,
                policy.workspacePolicy().getMaxFileBytes());
        assertFalse(policy.workspacePolicy().isFollowLinks());
        assertEquals(10000, policy.mediaJobStoreConfig().getMaxJobs());
        assertEquals(16L * WindowsSingleHostStoragePolicy.MIB,
                policy.mediaJobStoreConfig().getMaxFileBytes());
        assertEquals(5L * 60L * 1000L,
                policy.mediaCallbackSecurityPolicy().getMaxClockSkewMillis());
        assertEquals(100000, policy.mediaCallbackSecurityPolicy().getMaxTrackedNonces());
        assertEquals(180L * WindowsSingleHostStoragePolicy.DAY_MILLIS,
                policy.getAuditRetentionMillis());
        assertEquals(4L * WindowsSingleHostStoragePolicy.HOUR_MILLIS,
                policy.getBackupRtoMillis());
    }
}
