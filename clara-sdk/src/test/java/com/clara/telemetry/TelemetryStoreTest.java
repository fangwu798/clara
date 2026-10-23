package com.clara.telemetry;

import com.clara.core.telemetry.TelemetryEvent;
import com.clara.core.telemetry.TelemetrySpanKindEnum;
import com.clara.core.telemetry.TelemetryStatusEnum;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link TelemetryStore} 的落盘与回看测试。
 */
public class TelemetryStoreTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void writesJsonlAndSelectsLastTrace() throws Exception {
        Path root = folder.newFolder("workspace").toPath();
        TelemetryStore store = new TelemetryStore(root, 10, 7);
        try {
            store.onSpan(event("t1", "s1", null, "conversation.turn", TelemetryStatusEnum.OK));
            store.onSpan(event("t1", "s2", "s1", "model.call", TelemetryStatusEnum.OK));
            store.onSpan(event("t2", "s3", null, "conversation.turn", TelemetryStatusEnum.ERROR));

            assertEquals(3, store.size());
            List<TelemetryEvent> last = store.lastTrace();
            assertEquals(1, last.size());
            assertEquals("t2", last.get(0).getTraceId());

            assertEquals(2, store.trace("t1").size());
            assertTrue(store.recentTraceIds(5).contains("t1"));
        } finally {
            store.close();
        }

        Path file = root.resolve(".clara").resolve("telemetry")
                .resolve("traces-" + LocalDate.now() + ".jsonl");
        assertTrue(Files.isRegularFile(file));
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(3, lines.size());
        JsonNode node = new ObjectMapper().readTree(lines.get(0));
        assertEquals("conversation.turn", node.get("name").asText());
        assertEquals("t1", node.get("traceId").asText());
        assertNotNull(node.get("attributes"));
    }

    @Test
    public void recentQueueIsBounded() throws Exception {
        Path root = folder.newFolder("bounded").toPath();
        TelemetryStore store = new TelemetryStore(root, 2, 7);
        try {
            store.onSpan(event("t1", "s1", null, "a", TelemetryStatusEnum.OK));
            store.onSpan(event("t2", "s2", null, "b", TelemetryStatusEnum.OK));
            store.onSpan(event("t3", "s3", null, "c", TelemetryStatusEnum.OK));

            assertEquals(2, store.size());
            assertEquals(1, store.lastTrace().size());
            assertEquals("t3", store.lastTrace().get(0).getTraceId());
        } finally {
            store.close();
        }
    }

    private static TelemetryEvent event(String traceId, String spanId, String parent, String name,
            TelemetryStatusEnum status) {
        TelemetryEvent.Builder builder = TelemetryEvent.builder()
                .traceId(traceId)
                .spanId(spanId)
                .parentSpanId(parent)
                .name(name)
                .kind(TelemetrySpanKindEnum.CONVERSATION)
                .startEpochMillis(System.currentTimeMillis())
                .durationMillis(10L)
                .status(status);
        if (status == TelemetryStatusEnum.ERROR) {
            builder.errorCategory("EXTERNAL_SERVICE").errorCode("MODEL_PROVIDER_FAILED");
        }
        return builder.build();
    }
}
