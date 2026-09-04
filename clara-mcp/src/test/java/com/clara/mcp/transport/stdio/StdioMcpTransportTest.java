package com.clara.mcp.transport.stdio;

import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCodes;
import com.clara.mcp.client.JsonRpcNotification;
import com.clara.mcp.client.JsonRpcRequest;
import com.clara.mcp.client.JsonRpcResponse;
import com.clara.mcp.transport.JacksonMcpJsonCodec;
import com.clara.mcp.transport.McpTransportListener;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class StdioMcpTransportTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();
    private FakeProcess process;
    private StdioMcpTransport transport;
    private AtomicReference<Throwable> listenerFailure;

    @Before
    public void setUp() throws Exception {
        process = new FakeProcess(); listenerFailure = new AtomicReference<Throwable>();
        final Path executable = javaExecutable();
        transport = StdioMcpTransport.builder()
                .command(Arrays.asList(executable.toString(), "ignored"))
                .allowedExecutables(Arrays.asList(executable))
                .workingDirectory(temporaryFolder.newFolder("workspace").toPath())
                .codec(new JacksonMcpJsonCodec())
                .processFactory(new ProcessFactory() {
                    public Process start(java.util.List<String> command, Path directory,
                            java.util.Map<String, String> environment) { return process; }
                }).build();
        transport.start(new Listener(listenerFailure));
    }

    @After public void tearDown() { if (transport != null) transport.close(); }

    @Test
    public void correlatesResponseAndKeepsReadingAfterInvalidJson() throws Exception {
        process.sendStdout("not-json\n");
        CompletableFuture<JsonRpcResponse> response = transport.sendRequest(
                new JsonRpcRequest("1", "tools/list", Collections.<String, Object>emptyMap()), 1000L);
        process.sendStdout("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"ok\":true}}\n");
        assertEquals(Boolean.TRUE, response.get(2L, TimeUnit.SECONDS).getResult().get("ok"));
        waitUntilWritten("tools/list");
        assertTrue(listenerFailure.get() instanceof ClaraException);
    }

    @Test
    public void timesOutAndSendsCancellationNotification() throws Exception {
        CompletableFuture<JsonRpcResponse> response = transport.sendRequest(
                new JsonRpcRequest("timeout-id", "tools/list", Collections.<String, Object>emptyMap()), 30L);
        try {
            response.get(2L, TimeUnit.SECONDS); fail("expected timeout");
        } catch (ExecutionException exception) {
            assertEquals(ErrorCodes.MCP_REQUEST_TIMEOUT,
                    ((ClaraException) exception.getCause()).getError().getCode());
        }
        waitUntilWritten("notifications/cancelled");
    }

    @Test
    public void explicitCancellationRemovesPendingRequest() throws Exception {
        CompletableFuture<JsonRpcResponse> response = transport.sendRequest(
                new JsonRpcRequest("cancel-id", "tools/list", Collections.<String, Object>emptyMap()), 1000L);
        assertTrue(transport.cancel("cancel-id"));
        assertTrue(response.isCancelled());
        assertFalse(transport.cancel("cancel-id"));
        waitUntilWritten("notifications/cancelled");
    }

    @Test
    public void rejectsDuplicatePendingRequestId() throws Exception {
        transport.sendRequest(new JsonRpcRequest("same", "one",
                Collections.<String, Object>emptyMap()), 1000L);
        CompletableFuture<JsonRpcResponse> duplicate = transport.sendRequest(
                new JsonRpcRequest("same", "two", Collections.<String, Object>emptyMap()), 1000L);
        try {
            duplicate.get(); fail("expected duplicate failure");
        } catch (ExecutionException exception) {
            assertEquals(ErrorCodes.MCP_DUPLICATE_REQUEST_ID,
                    ((ClaraException) exception.getCause()).getError().getCode());
        }
    }

    private void waitUntilWritten(String value) throws Exception {
        long deadline = System.currentTimeMillis() + 2000L;
        while (!process.written().contains(value) && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
        assertTrue(process.written().contains(value));
    }

    private static Path javaExecutable() {
        String name = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Paths.get(System.getProperty("java.home"), "bin", name);
    }

    private static final class Listener implements McpTransportListener {
        private final AtomicReference<Throwable> failure;
        private Listener(AtomicReference<Throwable> failure) { this.failure = failure; }
        public void onNotification(JsonRpcNotification notification) { }
        public void onServerRequest(JsonRpcRequest request) { }
        public void onFailure(Throwable value) { failure.compareAndSet(null, value); }
    }

    private static final class FakeProcess extends Process {
        private final PipedInputStream stdout = new PipedInputStream();
        private final PipedOutputStream serverStdout;
        private final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        private final InputStream stderr = new ByteArrayInputStream(new byte[0]);
        private final CountDownLatch exited = new CountDownLatch(1);
        private volatile boolean alive = true;
        private FakeProcess() throws IOException { serverStdout = new PipedOutputStream(stdout); }
        private void sendStdout(String value) throws IOException {
            serverStdout.write(value.getBytes(StandardCharsets.UTF_8)); serverStdout.flush();
        }
        private synchronized String written() { return new String(stdin.toByteArray(), StandardCharsets.UTF_8); }
        public OutputStream getOutputStream() { return stdin; }
        public InputStream getInputStream() { return stdout; }
        public InputStream getErrorStream() { return stderr; }
        public int waitFor() throws InterruptedException { exited.await(); return 0; }
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return exited.await(timeout, unit);
        }
        public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        public void destroy() { alive = false; exited.countDown(); try { serverStdout.close(); } catch (IOException ignored) { } }
        public Process destroyForcibly() { destroy(); return this; }
        public boolean isAlive() { return alive; }
    }
}
