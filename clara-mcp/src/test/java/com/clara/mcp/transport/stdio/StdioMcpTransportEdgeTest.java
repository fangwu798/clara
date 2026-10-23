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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link StdioMcpTransport} 的边界行为：参数校验、超时、取消、通知派发与关闭。
 *
 * <p>为什么单独补这些：正常往返只覆盖了主路径，而线上更常见的是配置写错（相对路径、可执行文件不在白名单）、
 * 服务端卡死（超时）、进程提前退出、关闭时线程阻塞。这些分支不测就等于没有防回归。
 */
public class StdioMcpTransportEdgeTest {

    @Test
    public void builderRejectsInvalidLimitsAndMissingCodec() throws Exception {
        Path base = executable();
        expectBuilderFailure(base, builder(base).maxMessageBytes(0), "transport limits are invalid");
        expectBuilderFailure(base, builder(base).maxStderrBytes(-1), "transport limits are invalid");
        expectBuilderFailure(base, builder(base).codec(null), "codec must not be null");
        expectBuilderFailure(base, builder(base).workingDirectory(null), "workingDirectory must not be null");
        expectBuilderFailure(base, builder(base).workingDirectory(base.resolve("pom.xml")),
                "workingDirectory must be a directory");
        expectBuilderFailure(base, builder(base).allowedExecutables(Collections.<Path>emptyList()),
                "allowedExecutables must not be empty");
        expectBuilderFailure(base, builder(base).command(Collections.<String>emptyList()),
                "command must not be empty or contain null");
        expectBuilderFailure(base, StdioMcpTransport.builder()
                .command(Collections.singletonList("java"))
                .allowedExecutables(Collections.singletonList(base))
                .workingDirectory(base)
                .codec(new JacksonMcpJsonCodec()), "executable must be absolute");
        expectBuilderFailure(base, StdioMcpTransport.builder()
                .command(Collections.singletonList(base.resolve("pom.xml").toString()))
                .allowedExecutables(Collections.singletonList(base))
                .workingDirectory(base)
                .codec(new JacksonMcpJsonCodec()), "executable is not allowlisted");
        Map<String, String> withNull = new LinkedHashMap<>();
        withNull.put("A", null);
        expectBuilderFailure(base, builder(base).environment(withNull), "environment must not contain null");
    }

    @Test
    public void startValidatesListenerAndState() throws Exception {
        FakeProcess process = new FakeProcess();
        StdioMcpTransport transport = builder(executable()).processFactory(new FixedFactory(process)).build();
        try {
            transport.start(null);
            fail();
        } catch (IllegalArgumentException expected) {
            assertEquals("listener must not be null", expected.getMessage());
        }

        transport.start(new RecordingListener());
        try {
            transport.start(new RecordingListener());
            fail();
        } catch (IllegalStateException expected) {
            assertEquals("transport is already started", expected.getMessage());
        }
        transport.close();
        try {
            transport.start(new RecordingListener());
            fail();
        } catch (ClaraException expected) {
            assertEquals(ErrorCodes.MCP_TRANSPORT_CLOSED, expected.getError().getCode());
        }
    }

    @Test
    public void startFailureBecomesIoError() throws Exception {
        StdioMcpTransport transport = builder(executable()).processFactory(new IProcessFactory() {
            public Process start(java.util.List<String> command, Path workingDirectory,
                    Map<String, String> environment) throws IOException {
                throw new IOException("spawn failed");
            }
        }).build();
        try {
            transport.start(new RecordingListener());
            fail();
        } catch (ClaraException expected) {
            assertEquals(ErrorCodes.MCP_TRANSPORT_IO_ERROR, expected.getError().getCode());
            assertTrue("启动失败可以重试", expected.getError().getCategory().name().contains("EXTERNAL"));
        }
    }

    @Test
    public void sendRequestValidatesArgumentsAndState() throws Exception {
        FakeProcess process = new FakeProcess();
        StdioMcpTransport transport = builder(executable()).processFactory(new FixedFactory(process)).build();
        try {
            transport.sendRequest(null, 100L);
            fail();
        } catch (IllegalArgumentException expected) {
            assertEquals("request must not be null", expected.getMessage());
        }
        try {
            transport.sendRequest(new JsonRpcRequest("1", "ping", null), 0L);
            fail();
        } catch (IllegalArgumentException expected) {
            assertEquals("timeoutMillis must be positive", expected.getMessage());
        }
        try {
            transport.sendRequest(new JsonRpcRequest("1", "ping", null), 100L);
            fail();
        } catch (IllegalStateException expected) {
            assertEquals("transport is not started", expected.getMessage());
        }
        transport.close();
    }

    @Test
    public void duplicateRequestIdFailsFast() throws Exception {
        FakeProcess process = new FakeProcess();
        StdioMcpTransport transport = builder(executable()).processFactory(new FixedFactory(process)).build();
        transport.start(new RecordingListener());
        try {
            transport.sendRequest(new JsonRpcRequest("dup", "ping", null), 5000L);
            java.util.concurrent.CompletableFuture<JsonRpcResponse> second =
                    transport.sendRequest(new JsonRpcRequest("dup", "ping", null), 5000L);
            try {
                second.get(2, TimeUnit.SECONDS);
                fail("duplicate id should fail");
            } catch (ExecutionException expected) {
                assertEquals(ErrorCodes.MCP_DUPLICATE_REQUEST_ID,
                        ((ClaraException) expected.getCause()).getError().getCode());
            }
        } finally {
            transport.close();
        }
    }

    @Test
    public void requestTimesOutWithRetryableError() throws Exception {
        FakeProcess process = new FakeProcess();
        StdioMcpTransport transport = builder(executable()).processFactory(new FixedFactory(process)).build();
        transport.start(new RecordingListener());
        try {
            transport.sendRequest(new JsonRpcRequest("slow", "ping", null), 50L)
                    .get(2, TimeUnit.SECONDS);
            fail("should time out");
        } catch (ExecutionException expected) {
            ClaraException failure = (ClaraException) expected.getCause();
            assertEquals(ErrorCodes.MCP_REQUEST_TIMEOUT, failure.getError().getCode());
            assertTrue("超时通常可重试", failure.getError().isRetryable());
        } finally {
            transport.close();
        }
    }

    @Test
    public void cancelRemovesPendingRequest() throws Exception {
        FakeProcess process = new FakeProcess();
        StdioMcpTransport transport = builder(executable()).processFactory(new FixedFactory(process)).build();
        transport.start(new RecordingListener());
        try {
            java.util.concurrent.CompletableFuture<JsonRpcResponse> future =
                    transport.sendRequest(new JsonRpcRequest("cancel-me", "ping", null), 5000L);
            assertTrue(transport.cancel("cancel-me"));
            assertTrue(future.isCancelled());
            assertFalse("已移除的请求不能重复取消", transport.cancel("cancel-me"));
            try {
                transport.cancel(" ");
                fail();
            } catch (IllegalArgumentException expected) {
                assertEquals("requestId must not be blank", expected.getMessage());
            }
            try {
                transport.sendNotification(null);
                fail();
            } catch (IllegalArgumentException expected) {
                assertEquals("notification must not be null", expected.getMessage());
            }
        } finally {
            transport.close();
        }
    }

    @Test
    public void notificationsAndServerRequestsAreDispatched() throws Exception {
        FakeProcess process = new FakeProcess();
        RecordingListener listener = new RecordingListener();
        StdioMcpTransport transport = builder(executable()).processFactory(new FixedFactory(process)).build();
        transport.start(listener);
        try {
            process.push("{\"jsonrpc\":\"2.0\",\"method\":\"note\",\"params\":{\"x\":1}}");
            process.push("{\"jsonrpc\":\"2.0\",\"id\":\"srv-1\",\"method\":\"sampling\",\"params\":{}}");
            long deadline = System.currentTimeMillis() + 3000L;
            while (System.currentTimeMillis() < deadline && (listener.notifications.get() == 0
                    || listener.serverRequests.get() == 0)) {
                Thread.sleep(20L);
            }
            assertEquals(1, listener.notifications.get());
            assertEquals(1, listener.serverRequests.get());
        } finally {
            transport.close();
        }
    }

    @Test
    public void malformedStdoutOnlyNotifiesListener() throws Exception {
        FakeProcess process = new FakeProcess();
        RecordingListener listener = new RecordingListener();
        StdioMcpTransport transport = builder(executable()).processFactory(new FixedFactory(process)).build();
        transport.start(listener);
        try {
            process.push("not-json");
            long deadline = System.currentTimeMillis() + 3000L;
            while (System.currentTimeMillis() < deadline && listener.failures.get() == 0) {
                Thread.sleep(20L);
            }
            assertTrue("坏行只通知监听器，不应中断读取线程", listener.failures.get() > 0);
        } finally {
            transport.close();
        }
    }

    @Test
    public void healthCheckFailsWhenProcessDied() throws Exception {
        FakeProcess process = new FakeProcess();
        StdioMcpTransport transport = builder(executable()).processFactory(new FixedFactory(process)).build();
        transport.start(new RecordingListener());
        try {
            transport.checkHealth();
            process.alive.set(false);
            transport.checkHealth();
            fail("dead process should fail health check");
        } catch (ClaraException expected) {
            assertEquals(ErrorCodes.MCP_TRANSPORT_IO_ERROR, expected.getError().getCode());
        } finally {
            transport.close();
        }
    }

    @Test
    public void closeRestoresInterruptAndForciblyDestroys() throws Exception {
        FakeProcess process = new FakeProcess();
        process.interruptOnWait = true;
        StdioMcpTransport transport = builder(executable()).processFactory(new FixedFactory(process)).build();
        transport.start(new RecordingListener());
        transport.close();
        assertTrue("关闭时被中断，必须恢复中断标记", Thread.currentThread().isInterrupted());
        Thread.interrupted();
        assertTrue("等待超时/中断后必须强杀子进程", process.forciblyDestroyed.get());
        assertTrue(process.destroyed.get());
    }

    private static StdioMcpTransport.Builder builder(Path base) {
        return StdioMcpTransport.builder()
                .command(Collections.singletonList(base.toString()))
                .allowedExecutables(Collections.singletonList(base))
                .workingDirectory(base)
                .codec(new JacksonMcpJsonCodec());
    }

    private static Path executable() throws IOException {
        Path base = Paths.get(".").toRealPath();
        Assume.assumeFalse("脚本目录不能带空格，否则命令解析会歧义", base.toString().contains(" "));
        return base;
    }

    private static void expectBuilderFailure(Path base, StdioMcpTransport.Builder builder, String expected) {
        try {
            builder.build();
            fail("expected failure: " + expected);
        } catch (IllegalArgumentException failure) {
            assertEquals(expected, failure.getMessage());
        }
    }

    private static final class FixedFactory implements IProcessFactory {
        private final Process process;

        private FixedFactory(Process process) {
            this.process = process;
        }

        public Process start(java.util.List<String> command, Path workingDirectory,
                Map<String, String> environment) {
            return process;
        }
    }

    private static final class RecordingListener implements IMcpTransportListener {
        private final AtomicInteger notifications = new AtomicInteger();
        private final AtomicInteger serverRequests = new AtomicInteger();
        private final AtomicInteger failures = new AtomicInteger();

        public void onNotification(JsonRpcNotification notification) {
            notifications.incrementAndGet();
        }

        public void onServerRequest(JsonRpcRequest request) {
            serverRequests.incrementAndGet();
        }

        public void onFailure(Throwable failure) {
            failures.incrementAndGet();
        }
    }

    /**
     * 可主动推送 stdout 的假进程：写入不自动回包，便于构造超时与通知场景。
     */
    private static final class FakeProcess extends Process {
        private final PipedInputStream stdoutIn;
        private final PipedOutputStream stdoutOut;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicBoolean destroyed = new AtomicBoolean(false);
        private final AtomicBoolean forciblyDestroyed = new AtomicBoolean(false);
        private volatile boolean interruptOnWait;

        private FakeProcess() throws IOException {
            stdoutIn = new PipedInputStream(65536);
            stdoutOut = new PipedOutputStream(stdoutIn);
        }

        private void push(String json) throws IOException {
            stdoutOut.write(json.getBytes(StandardCharsets.UTF_8));
            stdoutOut.write('\n');
            stdoutOut.flush();
        }

        @Override
        public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override
                public void write(int value) {
                    // 丢弃：这些用例只关心请求登记与超时，不需要服务端回包。
                }
            };
        }

        @Override
        public InputStream getInputStream() {
            return stdoutIn;
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() throws InterruptedException {
            if (interruptOnWait) {
                throw new InterruptedException("interrupted");
            }
            while (alive.get()) {
                Thread.sleep(5L);
            }
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (interruptOnWait) {
                throw new InterruptedException("interrupted");
            }
            return true;
        }

        @Override
        public int exitValue() {
            if (alive.get()) {
                throw new IllegalThreadStateException("still running");
            }
            return 0;
        }

        @Override
        public void destroy() {
            destroyed.set(true);
            alive.set(false);
            try {
                stdoutOut.close();
            } catch (IOException ignored) {
                // 关闭管道失败不影响断言。
            }
        }

        @Override
        public Process destroyForcibly() {
            forciblyDestroyed.set(true);
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }
    }
}