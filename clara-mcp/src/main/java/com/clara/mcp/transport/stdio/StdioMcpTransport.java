package com.clara.mcp.transport.stdio;

import com.clara.exception.ErrorCategory;
import com.clara.exception.ErrorCodes;
import com.clara.mcp.transport.jsonrpc.JsonRpcNotification;
import com.clara.mcp.transport.jsonrpc.JsonRpcRequest;
import com.clara.mcp.transport.jsonrpc.JsonRpcResponse;
import com.clara.mcp.transport.McpDecodedMessage;
import com.clara.mcp.transport.McpJsonCodec;
import com.clara.mcp.transport.McpTransport;
import com.clara.mcp.transport.McpTransportErrors;
import com.clara.mcp.transport.McpTransportListener;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 管理本地 MCP 子进程，并通过逐行 JSON-RPC 在标准输入输出上发送请求、通知和取消消息。
 *
 * <p>常规用法：使用 {@link #builder} 配置命令、允许的可执行文件、工作目录和消息上限，构建后先调用
 * {@link #start}，再调用 {@link #sendRequest} 或 {@link #sendNotification}；最终调用 {@link #close()}。
 *
 * <p>注意：可执行文件必须在允许列表中，工作目录必须真实存在。请求 ID 在未完成期间必须唯一；每个请求都应
 * 设置正超时。关闭、子进程退出或协议读取失败会使全部待处理 Future 失败，stderr 消费器不得阻塞读取线程。
 */
public final class StdioMcpTransport implements McpTransport {
    private final List<String> command;
    private final Path workingDirectory;
    private final Map<String, String> environment;
    private final int maxMessageBytes;
    private final int maxStderrBytes;
    private final Consumer<String> stderrConsumer;
    private final McpJsonCodec codec;
    private final ProcessFactory processFactory;
    private final ScheduledExecutorService executor;
    private final ConcurrentMap<String, PendingRequest> pending =
            new ConcurrentHashMap<String, PendingRequest>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object writeLock = new Object();
    private volatile Process process;
    private volatile BufferedWriter writer;
    private volatile McpTransportListener listener;

    private StdioMcpTransport(Builder builder) {
        this.command = validateCommand(builder.command, builder.allowedExecutables);
        this.workingDirectory = validateWorkingDirectory(builder.workingDirectory);
        this.environment = immutableEnvironment(builder.environment);
        if (builder.maxMessageBytes <= 0 || builder.maxStderrBytes < 0) {
            throw new IllegalArgumentException("transport limits are invalid");
        }
        if (builder.codec == null) { throw new IllegalArgumentException("codec must not be null"); }
        this.maxMessageBytes = builder.maxMessageBytes;
        this.maxStderrBytes = builder.maxStderrBytes;
        this.stderrConsumer = builder.stderrConsumer;
        this.codec = builder.codec;
        this.processFactory = builder.processFactory == null
                ? new DefaultProcessFactory() : builder.processFactory;
        this.executor = Executors.newScheduledThreadPool(4, new DaemonThreadFactory());
    }

    public static Builder builder() { return new Builder(); }

    public synchronized void start(McpTransportListener listener) {
        if (listener == null) { throw new IllegalArgumentException("listener must not be null"); }
        if (closed.get()) { throw closedError(); }
        if (!started.compareAndSet(false, true)) { throw new IllegalStateException("transport is already started"); }
        this.listener = listener;
        try {
            process = processFactory.start(command, workingDirectory, environment);
            writer = new BufferedWriter(new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8));
            executor.execute(new Runnable() { public void run() { readStdout(); } });
            executor.execute(new Runnable() { public void run() { readStderr(); } });
            executor.execute(new Runnable() { public void run() { waitForExit(); } });
        } catch (IOException exception) {
            started.set(false);
            throw ioError();
        }
    }

    public CompletableFuture<JsonRpcResponse> sendRequest(final JsonRpcRequest request,
            long timeoutMillis) {
        if (request == null) { throw new IllegalArgumentException("request must not be null"); }
        if (timeoutMillis <= 0L) { throw new IllegalArgumentException("timeoutMillis must be positive"); }
        ensureRunning();
        final CompletableFuture<JsonRpcResponse> future = new CompletableFuture<JsonRpcResponse>();
        final PendingRequest item = new PendingRequest(future);
        if (pending.putIfAbsent(request.getId(), item) != null) {
            future.completeExceptionally(McpTransportErrors.error(ErrorCodes.MCP_DUPLICATE_REQUEST_ID,
                    ErrorCategory.CONFLICT, false));
            return future;
        }
        item.timeout = executor.schedule(new Runnable() {
            public void run() {
                if (pending.remove(request.getId(), item)) {
                    future.completeExceptionally(McpTransportErrors.error(ErrorCodes.MCP_REQUEST_TIMEOUT,
                            ErrorCategory.TIMEOUT, true));
                    writeCancellation(request.getId(), "timeout");
                }
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);
        future.whenComplete((response, failure) -> {
            if (future.isCancelled() && pending.remove(request.getId(), item)) {
                item.cancelTimeout(); writeCancellation(request.getId(), "cancelled");
            }
        });
        try {
            write(codec.encode(request));
        } catch (RuntimeException exception) {
            if (pending.remove(request.getId(), item)) {
                item.cancelTimeout(); future.completeExceptionally(exception);
            }
        }
        return future;
    }

    public void sendNotification(JsonRpcNotification notification) {
        if (notification == null) { throw new IllegalArgumentException("notification must not be null"); }
        ensureRunning(); write(codec.encode(notification));
    }

    public boolean cancel(String requestId) {
        if (requestId == null || requestId.trim().isEmpty()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        PendingRequest item = pending.remove(requestId);
        if (item == null) return false;
        item.cancelTimeout(); item.future.cancel(false);
        writeCancellation(requestId, "cancelled by caller");
        return true;
    }

    public void checkHealth() {
        ensureRunning();
        if (!process.isAlive()) { throw ioError(); }
    }

    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        failPending(closedError());
        BufferedWriter currentWriter = writer;
        if (currentWriter != null) try { currentWriter.close(); } catch (IOException ignored) { }
        Process current = process;
        if (current != null) {
            try { current.getInputStream().close(); } catch (IOException ignored) { }
            try { current.getErrorStream().close(); } catch (IOException ignored) { }
            current.destroy();
            try {
                if (!current.waitFor(2L, TimeUnit.SECONDS)) current.destroyForcibly();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt(); current.destroyForcibly();
            }
        }
        executor.shutdownNow();
    }

    private void readStdout() {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while (!closed.get() && (line = readBoundedLine(reader, maxMessageBytes)) != null) {
                if (line.trim().isEmpty()) continue;
                try { handle(line); }
                catch (RuntimeException exception) { listener.onFailure(exception); }
            }
            if (!closed.get()) signalFailure(ioError());
        } catch (IOException exception) {
            if (!closed.get()) signalFailure(ioError());
        } catch (RuntimeException exception) {
            if (!closed.get()) signalFailure(exception);
        }
    }

    private void handle(String json) {
        McpDecodedMessage message = codec.decode(json);
        if (message.getType() == McpDecodedMessage.Type.RESPONSE) {
            JsonRpcResponse response = message.getResponse();
            PendingRequest item = pending.remove(response.getId());
            if (item != null) { item.cancelTimeout(); item.future.complete(response); }
        } else if (message.getType() == McpDecodedMessage.Type.NOTIFICATION) {
            listener.onNotification(message.getNotification());
        } else {
            listener.onServerRequest(message.getRequest());
        }
    }

    private void readStderr() {
        if (process == null) return;
        int remaining = maxStderrBytes;
        char[] buffer = new char[2048];
        try {
            InputStreamReader reader = new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8);
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                if (remaining > 0 && stderrConsumer != null) {
                    int accepted = Math.min(count, remaining);
                    stderrConsumer.accept(new String(buffer, 0, accepted));
                    remaining -= accepted;
                }
            }
        } catch (IOException ignored) { }
    }

    private void waitForExit() {
        try {
            int exitCode = process.waitFor();
            if (!closed.get()) signalFailure(new IllegalStateException("MCP process exited: " + exitCode));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void signalFailure(RuntimeException failure) {
        failPending(failure);
        McpTransportListener current = listener;
        if (current != null) current.onFailure(failure);
    }

    private void failPending(RuntimeException failure) {
        for (Map.Entry<String, PendingRequest> entry : pending.entrySet()) {
            if (pending.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().cancelTimeout(); entry.getValue().future.completeExceptionally(failure);
            }
        }
    }

    private void writeCancellation(String requestId, String reason) {
        if (closed.get() || !started.get()) return;
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("requestId", requestId); params.put("reason", reason);
        try { write(codec.encode(new JsonRpcNotification("notifications/cancelled", params))); }
        catch (RuntimeException ignored) { }
    }

    private void write(String message) {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxMessageBytes) {
            throw McpTransportErrors.error(ErrorCodes.MCP_MESSAGE_TOO_LARGE,
                    ErrorCategory.RESOURCE_LIMIT, false);
        }
        try {
            synchronized (writeLock) {
                writer.write(message); writer.newLine(); writer.flush();
            }
        } catch (IOException exception) {
            throw ioError();
        }
    }

    private void ensureRunning() {
        if (closed.get()) { throw closedError(); }
        if (!started.get() || process == null) { throw new IllegalStateException("transport is not started"); }
    }

    private static String readBoundedLine(BufferedReader reader, int maxBytes) throws IOException {
        StringBuilder result = new StringBuilder();
        int value;
        while ((value = reader.read()) >= 0) {
            if (value == '\n') break;
            if (value != '\r') result.append((char) value);
            if (result.length() > maxBytes) {
                throw McpTransportErrors.error(ErrorCodes.MCP_MESSAGE_TOO_LARGE,
                        ErrorCategory.RESOURCE_LIMIT, false);
            }
        }
        if (value < 0 && result.length() == 0) return null;
        if (result.toString().getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw McpTransportErrors.error(ErrorCodes.MCP_MESSAGE_TOO_LARGE,
                    ErrorCategory.RESOURCE_LIMIT, false);
        }
        return result.toString();
    }

    private static List<String> validateCommand(List<String> command, List<Path> allowed) {
        if (command == null || command.isEmpty() || command.contains(null)) {
            throw new IllegalArgumentException("command must not be empty or contain null");
        }
        if (allowed == null || allowed.isEmpty()) {
            throw new IllegalArgumentException("allowedExecutables must not be empty");
        }
        try {
            Path executable = java.nio.file.Paths.get(command.get(0));
            if (!executable.isAbsolute()) { throw new IllegalArgumentException("executable must be absolute"); }
            executable = executable.toRealPath();
            boolean approved = false;
            for (Path candidate : allowed) {
                if (candidate != null && executable.equals(candidate.toRealPath())) { approved = true; break; }
            }
            if (!approved) { throw new IllegalArgumentException("executable is not allowlisted"); }
            List<String> result = new ArrayList<String>(command);
            result.set(0, executable.toString());
            return Collections.unmodifiableList(result);
        } catch (IOException exception) {
            throw new IllegalArgumentException("executable cannot be resolved", exception);
        }
    }

    private static Path validateWorkingDirectory(Path path) {
        if (path == null) { throw new IllegalArgumentException("workingDirectory must not be null"); }
        try {
            Path real = path.toRealPath();
            if (!Files.isDirectory(real)) { throw new IllegalArgumentException("workingDirectory must be a directory"); }
            return real;
        } catch (IOException exception) {
            throw new IllegalArgumentException("workingDirectory cannot be resolved", exception);
        }
    }

    private static Map<String, String> immutableEnvironment(Map<String, String> source) {
        if (source == null) return Collections.emptyMap();
        if (source.containsKey(null) || source.containsValue(null)) {
            throw new IllegalArgumentException("environment must not contain null");
        }
        return Collections.unmodifiableMap(new LinkedHashMap<String, String>(source));
    }

    private static RuntimeException closedError() {
        return McpTransportErrors.error(ErrorCodes.MCP_TRANSPORT_CLOSED, ErrorCategory.CANCELLED, false);
    }
    private static RuntimeException ioError() {
        return McpTransportErrors.error(ErrorCodes.MCP_TRANSPORT_IO_ERROR, ErrorCategory.EXTERNAL_SERVICE, true);
    }

    /**
     * 封装待处理操作的输入参数。
     */
    private static final class PendingRequest {
        private final CompletableFuture<JsonRpcResponse> future;
        private volatile ScheduledFuture<?> timeout;
        private PendingRequest(CompletableFuture<JsonRpcResponse> future) { this.future = future; }
        private void cancelTimeout() { if (timeout != null) timeout.cancel(false); }
    }

    /**
     * 提供进程工厂的默认实现。
     */
    private static final class DefaultProcessFactory implements ProcessFactory {
        public Process start(List<String> command, Path workingDirectory,
                Map<String, String> environment) throws IOException {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workingDirectory.toFile());
            builder.environment().clear(); builder.environment().putAll(environment);
            return builder.start();
        }
    }

    /**
     * 负责创建守护进程线程实例。
     */
    private static final class DaemonThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "clara-mcp-stdio-" + sequence.incrementAndGet());
            thread.setDaemon(true); return thread;
        }
    }

    /**
     * 表示。
     */
    public static final class Builder {
        private List<String> command;
        private List<Path> allowedExecutables;
        private Path workingDirectory;
        private Map<String, String> environment = Collections.emptyMap();
        private int maxMessageBytes = 1024 * 1024;
        private int maxStderrBytes = 64 * 1024;
        private Consumer<String> stderrConsumer;
        private McpJsonCodec codec;
        private ProcessFactory processFactory;
        public Builder command(List<String> value) { command = value; return this; }
        public Builder allowedExecutables(List<Path> value) { allowedExecutables = value; return this; }
        public Builder workingDirectory(Path value) { workingDirectory = value; return this; }
        public Builder environment(Map<String, String> value) { environment = value; return this; }
        public Builder maxMessageBytes(int value) { maxMessageBytes = value; return this; }
        public Builder maxStderrBytes(int value) { maxStderrBytes = value; return this; }
        public Builder stderrConsumer(Consumer<String> value) { stderrConsumer = value; return this; }
        public Builder codec(McpJsonCodec value) { codec = value; return this; }
        public Builder processFactory(ProcessFactory value) { processFactory = value; return this; }
        public StdioMcpTransport build() { return new StdioMcpTransport(this); }
    }
}
