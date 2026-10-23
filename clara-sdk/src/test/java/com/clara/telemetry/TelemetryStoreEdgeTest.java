package com.clara.telemetry;

import com.clara.core.telemetry.TelemetryEvent;
import com.clara.core.telemetry.TelemetrySpanKindEnum;
import com.clara.core.telemetry.TelemetryStatusEnum;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Degradation and retention boundaries of {@link TelemetryStore}: an unavailable directory keeps
 * events in memory only, and expired files are removed according to the retention window.
 */
public class TelemetryStoreEdgeTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void nullWorkspaceKeepsEventsInMemoryOnly() {
        TelemetryStore store = new TelemetryStore(null, 1, 0);
        try {
            assertNull(store.directory());
            store.onSpan(event("t1", "s1", null, 1L));
            store.onSpan(event("t2", "s2", null, 2L));
            assertEquals(1, store.size());
            assertEquals("t2", store.lastTrace().get(0).getTraceId());
        } finally {
            store.close();
        }
    }

    @Test
    public void unreadableDirectoryDegradesToMemoryOnly() throws Exception {
        Path root = folder.newFolder("degraded").toPath();
        Files.write(root.resolve(".clara"), new byte[]{1});

        TelemetryStore store = new TelemetryStore(root, 0, 0);
        try {
            assertNull(store.directory());
            store.onSpan(event("t1", "s1", null, 1L));
            assertEquals(1, store.size());
        } finally {
            store.close();
        }
    }

    @Test
    public void traceQueriesHandleBlankIdsAndSortByStartThenSpan() {
        TelemetryStore store = new TelemetryStore(null, 10, 0);
        try {
            assertTrue(store.trace(null).isEmpty());
            assertTrue(store.trace("   ").isEmpty());
            assertTrue(store.lastTrace().isEmpty());
            store.onSpan(event("t1", "s2", null, 20L));
            store.onSpan(event("t1", "s1", null, 10L));
            assertEquals("s1", store.trace("t1").get(0).getSpanId());
            assertEquals(1, store.recentTraceIds(0).size());
        } finally {
            store.close();
        }
    }

    @Test
    public void cleanupRemovesOnlyExpiredDatedFiles() throws Exception {
        Path root = folder.newFolder("retention").toPath();
        Path telemetry = root.resolve(".clara").resolve("telemetry");
        Files.createDirectories(telemetry);
        Path expired = telemetry.resolve("traces-" + LocalDate.now().minusDays(10) + ".jsonl");
        Path kept = telemetry.resolve("traces-not-a-date.jsonl");
        Files.write(expired, "old".getBytes(StandardCharsets.UTF_8));
        Files.write(kept, "keep".getBytes(StandardCharsets.UTF_8));

        TelemetryStore store = new TelemetryStore(root, 5, 3);
        try {
            assertTrue("expired trace file must be removed", !Files.exists(expired));
            assertTrue("files with an unknown date format must be kept", Files.exists(kept));
        } finally {
            store.close();
        }
    }

    @Test
    public void nullEventAndMinimalEventAreAccepted() {
        TelemetryStore store = new TelemetryStore(null, 5, 0);
        try {
            assertEquals(0, store.size());
            store.onSpan(null);
            store.onSpan(event("t", null, null, 0L));
            assertEquals(1, store.size());
            assertEquals("t", store.trace("t").get(0).getTraceId());
        } finally {
            store.close();
        }
    }

    private static TelemetryEvent event(String traceId, String spanId, String parent, long start) {
        return TelemetryEvent.builder()
                .traceId(traceId)
                .spanId(spanId == null ? "s-null" : spanId)
                .parentSpanId(parent)
                .name("test.span")
                .kind(TelemetrySpanKindEnum.OTHER)
                .startEpochMillis(start)
                .durationMillis(1L)
                .status(TelemetryStatusEnum.OK)
                .attributes(new java.util.LinkedHashMap<String, String>())
                .build();
    }
}
