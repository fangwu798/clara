package com.clara.media.runtime;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link MediaRuntime} 的单元测试：惰性装配（未使用不落盘）、装配路径与关闭后释放文件锁。
 */
public class MediaRuntimeTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void staysLazyUntilFirstUse() throws Exception {
        Path root = folder.newFolder("workspace").toPath();
        MediaRuntime runtime = new MediaRuntime(root, "test");
        try {
            assertFalse(Files.exists(root.resolve(".clara")));

            assertNotNull(runtime.mediaStore());
            assertTrue(Files.exists(root.resolve(".clara").resolve("assets")));
        } finally {
            runtime.close();
        }
    }

    @Test
    public void releasesAssetDirectoryLockOnClose() throws Exception {
        Path root = folder.newFolder("workspace").toPath();
        MediaRuntime first = new MediaRuntime(root, "test");
        first.mediaStore();
        first.close();

        MediaRuntime second = new MediaRuntime(root, "test");
        try {
            assertNotNull(second.mediaStore());
        } finally {
            second.close();
        }
    }

    @Test
    public void rejectsInvalidArguments() throws Exception {
        Path root = folder.newFolder("workspace").toPath();

        assertThrows(IllegalArgumentException.class, () -> new MediaRuntime(null, "test"));
        assertThrows(IllegalArgumentException.class, () -> new MediaRuntime(root, "  "));
    }
}
