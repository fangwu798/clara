package com.clara.adapter.console;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link LogRetention} 的单元测试：按文件名保留最近 N 份日志，异常与非法配置都不影响主流程。
 */
public class LogRetentionTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void pruneKeepsNewestFiles() throws Exception {
        File dir = folder.newFolder("logs");
        for (int hour = 1; hour <= 5; hour++) {
            File file = new File(dir, String.format("clara-20260101-%02d0000.log", hour));
            Files.write(file.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        }
        Files.write(new File(dir, "other.txt").toPath(), "keep".getBytes(StandardCharsets.UTF_8));

        LogRetention.prune(dir.toPath(), 2);

        List<String> names = new ArrayList<>();
        for (File file : dir.listFiles()) {
            names.add(file.getName());
        }
        Collections.sort(names);
        assertEquals(3, names.size());
        assertTrue(names.contains("clara-20260101-040000.log"));
        assertTrue(names.contains("clara-20260101-050000.log"));
        assertTrue("非日志文件不应被清理", names.contains("other.txt"));
    }

    @Test
    public void pruneIgnoresNonPositiveKeepAndMissingDirectory() {
        LogRetention.prune(null, 3);
        LogRetention.prune(folder.getRoot().toPath(), 0);
    }

    @Test
    public void keepCountFallsBackToDefaultOnInvalidValue() {
        String original = System.getProperty(LogRetention.KEEP_PROPERTY);
        try {
            System.clearProperty(LogRetention.KEEP_PROPERTY);
            assertEquals(LogRetention.DEFAULT_KEEP, LogRetention.keepCount());

            System.setProperty(LogRetention.KEEP_PROPERTY, "7");
            assertEquals(7, LogRetention.keepCount());

            System.setProperty(LogRetention.KEEP_PROPERTY, "-1");
            assertEquals(LogRetention.DEFAULT_KEEP, LogRetention.keepCount());

            System.setProperty(LogRetention.KEEP_PROPERTY, "abc");
            assertEquals(LogRetention.DEFAULT_KEEP, LogRetention.keepCount());
        } finally {
            if (original == null) {
                System.clearProperty(LogRetention.KEEP_PROPERTY);
            } else {
                System.setProperty(LogRetention.KEEP_PROPERTY, original);
            }
        }
        assertFalse(LogRetention.DEFAULT_KEEP <= 0);
    }
}