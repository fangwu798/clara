package com.clara.adapter.console;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 运行日志保留策略：每轮对话都会新建一个 {@code clara-<时间戳>.log}，这里按文件名（即时间戳）保留最近若干份。
 *
 * <p>文件名格式固定为 {@code clara-yyyyMMdd-HHmmss.log}，字典序即时间序，无需读取文件属性。
 * 清理失败只影响磁盘占用，不影响对话，因此所有异常都静默忽略。
 */
final class LogRetention {

    /**
     * 默认保留的日志份数。
     */
    static final int DEFAULT_KEEP = 30;

    /**
     * 覆盖保留份数的系统属性；非正整数时按默认值处理。
     */
    static final String KEEP_PROPERTY = "clara.logs.keep";

    private static final String PATTERN = "clara-*.log";

    private LogRetention() {
    }

    /**
     * 读取保留份数：{@code -Dclara.logs.keep=N} 优先，非法值退回默认值。
     *
     * @return 保留份数，始终为正
     */
    static int keepCount() {
        String configured = System.getProperty(KEEP_PROPERTY);
        if (configured == null) {
            return DEFAULT_KEEP;
        }
        try {
            int value = Integer.parseInt(configured.trim());
            return value > 0 ? value : DEFAULT_KEEP;
        } catch (NumberFormatException ignored) {
            return DEFAULT_KEEP;
        }
    }

    /**
     * 清理日志目录，只保留文件名最大的 {@code keep} 份。
     *
     * @param directory 日志目录，可为 null
     * @param keep      保留份数，小于等于 0 时不清理
     */
    static void prune(Path directory, int keep) {
        if (directory == null || keep <= 0) {
            return;
        }
        List<Path> logs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, PATTERN)) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)) {
                    logs.add(file);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            return;
        }
        if (logs.size() <= keep) {
            return;
        }
        logs.sort(Comparator.comparing(path -> path.getFileName().toString(), Comparator.reverseOrder()));
        for (int index = keep; index < logs.size(); index++) {
            try {
                Files.deleteIfExists(logs.get(index));
            } catch (IOException | RuntimeException ignored) {
                // 清理是尽力而为：单个文件删不掉不影响本轮日志写入。
            }
        }
    }
}