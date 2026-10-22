package com.clara.telemetry;

import com.clara.core.telemetry.ITelemetrySink;
import com.clara.core.telemetry.TelemetryEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 本地链路存储：把 Span 事件写入 {@code <工作目录>/.clara/telemetry/traces-YYYY-MM-DD.jsonl}，同时在内存保留
 * 最近若干条，供 {@code /trace} 即时回看。
 *
 * <p>设计取舍：
 * <ul>
 *   <li>写文件失败不抛出、不影响对话，只在首次失败时提示一次；</li>
 *   <li>最近事件队列有上限，避免长时间会话把内存撑大；磁盘文件按天滚动，按保留天数清理；</li>
 *   <li>事件本身不含提示词正文、工具入参和密钥，落盘前无需再做内容脱敏。</li>
 * </ul>
 */
public final class TelemetryStore implements ITelemetrySink, Closeable {

    private static final String DIRECTORY = ".clara";
    private static final String TELEMETRY = "telemetry";
    private static final String FILE_PREFIX = "traces-";
    private static final String FILE_SUFFIX = ".jsonl";
    private static final int DEFAULT_MAX_RECENT = 500;

    private final ObjectMapper json = new ObjectMapper();
    private final Deque<TelemetryEvent> recent = new ArrayDeque<>();
    private final int maxRecent;
    private final Path directory;
    private final int retainDays;
    private final Object fileLock = new Object();

    private BufferedWriter writer;
    private LocalDate writerDate;
    private boolean writeFailureReported;

    /**
     * 创建本地链路存储。
     *
     * @param workspaceRoot 工作目录，非 null
     * @param maxRecent     内存保留事件上限，小于等于 0 时使用默认值
     * @param retainDays    磁盘保留天数，小于等于 0 表示不清理
     */
    public TelemetryStore(Path workspaceRoot, int maxRecent, int retainDays) {
        this.maxRecent = maxRecent > 0 ? maxRecent : DEFAULT_MAX_RECENT;
        this.retainDays = retainDays;
        Path root = workspaceRoot == null ? null : workspaceRoot.resolve(DIRECTORY).resolve(TELEMETRY);
        this.directory = prepareDirectory(root);
        if (this.directory != null && retainDays > 0) {
            cleanup();
        }
    }

    @Override
    public void onSpan(TelemetryEvent event) {
        if (event == null) {
            return;
        }
        synchronized (recent) {
            recent.addLast(event);
            while (recent.size() > maxRecent) {
                recent.removeFirst();
            }
        }
        append(event);
    }

    /**
     * 取最近一条 trace 的全部事件。
     *
     * @return 按开始时间排序的事件列表，非 null（可为空）
     */
    public List<TelemetryEvent> lastTrace() {
        String traceId;
        synchronized (recent) {
            traceId = recent.isEmpty() ? null : recent.peekLast().getTraceId();
        }
        return trace(traceId);
    }

    /**
     * 按 traceId 取事件；传 null 或空白时返回空列表。
     *
     * @param traceId 链路标识，可为 null
     * @return 按开始时间排序的事件列表，非 null
     */
    public List<TelemetryEvent> trace(String traceId) {
        if (traceId == null || traceId.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<TelemetryEvent> selected = new ArrayList<>();
        synchronized (recent) {
            for (TelemetryEvent event : recent) {
                if (traceId.equals(event.getTraceId())) {
                    selected.add(event);
                }
            }
        }
        return sorted(selected);
    }

    /**
     * 取最近出现过的 traceId（去重，最新的在前）。
     *
     * @param limit 最多返回条数
     * @return 链路标识列表，非 null
     */
    public List<String> recentTraceIds(int limit) {
        int max = limit <= 0 ? 5 : limit;
        Set<String> ids = new LinkedHashSet<>();
        synchronized (recent) {
            for (java.util.Iterator<TelemetryEvent> iterator = recent.descendingIterator();
                    iterator.hasNext();) {
                ids.add(iterator.next().getTraceId());
                if (ids.size() >= max) {
                    break;
                }
            }
        }
        return new ArrayList<>(ids);
    }

    /**
     * 最近事件条数，供启动提示与测试使用。
     *
     * @return 条数，非负
     */
    public int size() {
        synchronized (recent) {
            return recent.size();
        }
    }

    /**
     * 链路目录；创建失败时为 null。
     *
     * @return 目录或 null
     */
    public Path directory() {
        return directory;
    }

    @Override
    public void close() {
        synchronized (fileLock) {
            closeWriter();
        }
    }

    private static Path prepareDirectory(Path directory) {
        if (directory == null) {
            return null;
        }
        try {
            Files.createDirectories(directory);
            return directory;
        } catch (IOException failure) {
            System.err.println("[telemetry] 链路目录不可用，本次只保留内存记录：" + failure.getMessage()); // NOSONAR CLI output by design
            return null;
        }
    }

    private void append(TelemetryEvent event) {
        if (directory == null) {
            return;
        }
        synchronized (fileLock) {
            try {
                LocalDate today = LocalDate.now();
                if (writer == null || !today.equals(writerDate)) {
                    closeWriter();
                    writer = Files.newBufferedWriter(file(today), StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    writerDate = today;
                }
                writer.write(json.writeValueAsString(event));
                writer.newLine();
                writer.flush();
            } catch (IOException failure) {
                if (!writeFailureReported) {
                    writeFailureReported = true;
                    System.err.println("[telemetry] 链路写盘失败，后续只保留内存记录：" + failure.getMessage()); // NOSONAR CLI output by design
                }
                closeWriter();
            }
        }
    }

    private Path file(LocalDate date) {
        return directory.resolve(FILE_PREFIX + date + FILE_SUFFIX);
    }

    private void closeWriter() {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException ignored) {
            // 关闭失败没有补救动作，下一次写盘会重新打开。
        } finally {
            writer = null;
            writerDate = null;
        }
    }

    private void cleanup() {
        LocalDate threshold = LocalDate.now().minusDays(retainDays);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, FILE_PREFIX + "*" + FILE_SUFFIX)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                String date = name.substring(FILE_PREFIX.length(), name.length() - FILE_SUFFIX.length());
                try {
                    if (LocalDate.parse(date).isBefore(threshold)) {
                        Files.deleteIfExists(file);
                    }
                } catch (RuntimeException ignored) {
                    // 文件名不是预期日期格式时保留，避免误删用户文件。
                }
            }
        } catch (IOException ignored) {
            // 清理是尽力而为，不影响本次会话。
        }
    }

    private static List<TelemetryEvent> sorted(List<TelemetryEvent> events) {
        Collections.sort(events, (left, right) -> {
            int byStart = Long.compare(left.getStartEpochMillis(), right.getStartEpochMillis());
            return byStart != 0 ? byStart : left.getSpanId().compareTo(right.getSpanId());
        });
        return events;
    }
}
