package com.clara.mcp.transport.stdio;

import com.clara.exception.ClaraException;
import com.clara.exception.ErrorCodes;
import com.clara.mcp.api.IMcpTransportListener;
import com.clara.mcp.api.IProcessFactory;
import com.clara.mcp.transport.JacksonMcpJsonCodec;
import com.clara.mcp.transport.jsonrpc.JsonRpcNotification;
import com.clara.mcp.transport.jsonrpc.JsonRpcRequest;
import com.clara.mcp.transport.jsonrpc.JsonRpcResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link StdioMcpTransport} 的行为测试：用假 {@link IProcessFactory} 做逐行 JSON-RPC 往返，不启动真实进程。
 */
public class StdioMcpTransportTest {
    private static final String RESPONSE = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"ok\":true}}";

    @Test
    public void sendRequestWritesLineAndReceivesResponse() throws Exception {
        Path base = Paths.get(".").toRealPath();
        Assume.assumeFalse("working directory must not contain spaces for command parsing",
                base.toString().contains(" "));
        FakeProcess process = new FakeProcess();
        final StringBuilder stderr = new StringBuilder();
        StdioMcpTransport transport = StdioMcpTransport.builder()
                .command(Collections.singletonList(base.toString()))
                .allowedExecutables(Collections.singletonList(base))
                .workingDirectory(base)
                .codec(new JacksonMcpJsonCodec())
                .processFactory(new FakeProcessFactory(process))
                .maxMessageBytes(4096)
                .stderrConsumer(new java.util.function.Consumer<String>() {
                    public void accept(String value) { stderr.append(value); }
                })
                .build();

        transport.start(new SilentListener());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("x", 1);
        JsonRpcResponse response = transport.sendRequest(new JsonRpcRequest("1", "ping", params), 2000L)
                .get(3, TimeUnit.SECONDS);

        assertEquals("1", response.getId());
        assertEquals(Boolean.TRUE, response.getResult().get("ok"));
        String written = process.writtenLines();
        assertTrue(written.contains("\"method\":\"ping\""));
        assertTrue(written.contains("\"id\":\"1\""));
        awaitContains(stderr, "warn");

        transport.close();
        assertTrue(process.destroyed.get());
        transport.close();
        assertTrue(process.destroyed.get());
    }

    @Test
    public void oversizedOutgoingMessageFailsFuture() throws Exception {
        Path base = Paths.get(".").toRealPath();
        Assume.assumeFalse(base.toString().contains(" "));
        FakeProcess process = new FakeProcess();
        final StdioMcpTransport transport = StdioMcpTransport.builder()
                .command(Collections.singletonList(base.toString()))
                .allowedExecutables(Collections.singletonList(base))
                .workingDirectory(base)
                .codec(new JacksonMcpJsonCodec())
                .processFactory(new FakeProcessFactory(process))
                .maxMessageBytes(8)
                .build();

        transport.start(new SilentListener());
        try {
            transport.sendRequest(new JsonRpcRequest("1", "ping-too-long", null), 2000L)
                    .get(3, TimeUnit.SECONDS);
            fail("expected ExecutionException");
        } catch (ExecutionException exception) {
            assertTrue(exception.getCause() instanceof ClaraException);
            assertEquals(ErrorCodes.MCP_MESSAGE_TOO_LARGE,
                    ((ClaraException) exception.getCause()).getError().getCode());
        } finally {
            transport.close();
        }
    }

    private static void awaitContains(StringBuilder buffer, String expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000L;
        while (System.currentTimeMillis() < deadline && buffer.indexOf(expected) < 0) {
            Thread.sleep(20L);
        }
        assertTrue("stderr should contain " + expected + " but was: " + buffer, buffer.indexOf(expected) >= 0);
    }

    private static final class SilentListener implements IMcpTransportListener {
        public void onNotification(JsonRpcNotification notification) { }
        public void onServerRequest(JsonRpcRequest request) { }
        public void onFailure(Throwable failure) { }
    }

    private static final class FakeProcessFactory implements IProcessFactory {
        private final Process process;

        private FakeProcessFactory(Process process) { this.process = process; }

        public Process start(java.util.List<String> command, Path workingDirectory,
                Map<String, String> environment) {
            return process;
        }
    }

    /**
     * 假子进程：标准输入写入即回灌一条响应到标准输出，标准错误预置内容后 EOF。
     */
    private static final class FakeProcess extends Process {
        private final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        private final PipedInputStream stdoutIn;
        private final PipedOutputStream stdoutOut;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicBoolean destroyed = new AtomicBoolean(false);
        private final CountDownLatch exited = new CountDownLatch(1);

        private FakeProcess() {
            try {
                stdoutIn = new PipedInputStream(65536);
                stdoutOut = new PipedOutputStream(stdoutIn);
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        private String writtenLines() {
            return new String(stdin.toByteArray(), StandardCharsets.UTF_8);
        }

        @Override
        public OutputStream getOutputStream() {
            return new OutputStream() {
                private final ByteArrayOutputStream line = new ByteArrayOutputStream();

                @Override
                public void write(int value) throws IOException {
                    stdin.write(value);
                    if (value == '\n') {
                        line.reset();
                        stdoutOut.write(RESPONSE.getBytes(StandardCharsets.UTF_8));
                        stdoutOut.write('\n');
                        stdoutOut.flush();
                    } else {
                        line.write(value);
                    }
                }

                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    for (int index = 0; index < length; index++) {
                        write(bytes[offset + index]);
                    }
                }
            };
        }

        @Override
        public InputStream getInputStream() { return stdoutIn; }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream("warn\n".getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public int waitFor() throws InterruptedException {
            exited.await();
            return 0;
        }

        @Override
        public int exitValue() {
            if (alive.get()) { throw new IllegalThreadStateException("still running"); }
            return 0;
        }

        @Override
        public void destroy() {
            destroyed.set(true);
            alive.set(false);
            exited.countDown();
        }

        @Override
        public boolean isAlive() { return alive.get(); }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }
    }
}
