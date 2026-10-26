package com.clara.core.diagnostics;

import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link Diagnostics} 的单元测试：默认只吐告警、安装端口后按端口分发、reset 恢复默认。
 */
public class DiagnosticsTest {

    @After
    public void tearDown() {
        Diagnostics.reset();
    }

    @Test
    public void defaultSinkSilencesInfoAndWarnsToStderr() throws Exception {
        PrintStream originalErr = System.err;
        PrintStream originalOut = System.out;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(err, true, "UTF-8"));
            System.setOut(new PrintStream(out, true, "UTF-8"));
            Diagnostics.info("progress");
            Diagnostics.warn("degraded");
        } finally {
            System.setErr(originalErr);
            System.setOut(originalOut);
        }

        assertEquals("", new String(out.toByteArray(), StandardCharsets.UTF_8));
        assertTrue(new String(err.toByteArray(), StandardCharsets.UTF_8).contains("degraded"));
    }

    @Test
    public void installedSinkReceivesBothChannels() {
        RecordingSink sink = new RecordingSink();
        Diagnostics.install(sink);

        Diagnostics.info("progress");
        Diagnostics.warn("degraded");
        Diagnostics.info(null);

        assertEquals(1, sink.info.size());
        assertEquals("progress", sink.info.get(0));
        assertEquals(1, sink.warn.size());
        assertEquals("degraded", sink.warn.get(0));
    }

    @Test
    public void resetRestoresDefaultBehavior() {
        RecordingSink sink = new RecordingSink();
        Diagnostics.install(sink);
        Diagnostics.reset();

        Diagnostics.info("ignored");
        assertTrue(sink.info.isEmpty());
    }

    private static final class RecordingSink implements IDiagnosticsSink {
        private final List<String> info = new ArrayList<>();
        private final List<String> warn = new ArrayList<>();

        @Override
        public void info(String message) {
            info.add(message);
        }

        @Override
        public void warn(String message) {
            warn.add(message);
        }
    }
}
