package com.clara.media;

import com.clara.config.ConfigLocator;
import com.clara.utils.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 视频产物本地下载器：把视频平台返回的临时 URL 落到工作目录，避免链接过期后无法回看。
 *
 * <p>默认保存目录为 {@code <工作目录>/.clara/media/videos}，文件名带时间戳与序号。
 * 下载失败由调用方决定是否降级：视频已经生成（已产生费用）时，URL 仍应回传给用户。
 */
public final class VideoDownloader {

    /** 单文件大小上限：防止误下超大文件占满磁盘。 */
    public static final long DEFAULT_MAX_BYTES = 512L * 1024L * 1024L;

    private static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int DEFAULT_READ_TIMEOUT_MILLIS = 120_000;
    private static final String DEFAULT_EXTENSION = ".mp4";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path directory;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final long maxBytes;

    /**
     * 使用默认目录与超时创建下载器。
     */
    public VideoDownloader() {
        this(ConfigLocator.workspaceRoot().resolve(".clara").resolve("media").resolve("videos"),
                DEFAULT_CONNECT_TIMEOUT_MILLIS, DEFAULT_READ_TIMEOUT_MILLIS, DEFAULT_MAX_BYTES);
    }

    /**
     * 创建可注入目录与限制的下载器（便于测试）。
     *
     * @param directory           保存目录，不可为 null
     * @param connectTimeoutMillis 连接超时（毫秒），必须为正
     * @param readTimeoutMillis    读取超时（毫秒），必须为正
     * @param maxBytes             单文件上限（字节），必须为正
     * @throws IllegalArgumentException 参数非法时
     */
    public VideoDownloader(Path directory, int connectTimeoutMillis, int readTimeoutMillis, long maxBytes) {
        if (directory == null || connectTimeoutMillis <= 0 || readTimeoutMillis <= 0 || maxBytes <= 0) {
            throw new IllegalArgumentException("video downloader settings must be positive");
        }
        this.directory = directory;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        this.maxBytes = maxBytes;
    }

    /**
     * 依次下载视频引用。
     *
     * @param references 视频 URL 列表，可为 null / 空
     * @return 已保存的本地绝对路径，非 null
     * @throws IllegalStateException 创建目录或任一文件下载失败时
     */
    public List<Path> download(List<String> references) {
        if (references == null || references.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            Files.createDirectories(directory);
        } catch (IOException failure) {
            throw new IllegalStateException("创建视频目录失败：" + directory + "，" + failure.getMessage(), failure);
        }
        List<Path> saved = new ArrayList<>();
        int index = 0;
        for (String reference : references) {
            if (StringUtils.isBlank(reference)) {
                continue;
            }
            index++;
            Path target = directory.resolve(fileName(index, reference));
            downloadOne(reference.trim(), target);
            saved.add(target.toAbsolutePath());
        }
        return saved;
    }

    private void downloadOne(String reference, Path target) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(reference).openConnection();
            connection.setConnectTimeout(connectTimeoutMillis);
            connection.setReadTimeout(readTimeoutMillis);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Clara/1.0");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status);
            }
            copy(connection.getInputStream(), target);
        } catch (IOException | RuntimeException failure) {
            deleteQuietly(target);
            throw new IllegalStateException("下载视频失败（" + reference + "）：" + failure.getMessage(), failure);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void copy(InputStream input, Path target) throws IOException {
        long total = 0L;
        try (InputStream in = input; OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("超过单文件上限 " + maxBytes + " 字节");
                }
                out.write(buffer, 0, read);
            }
        }
    }

    private static String fileName(int index, String reference) {
        return "clara-video-" + LocalDateTime.now().format(STAMP) + "-" + index + extension(reference);
    }

    private static String extension(String reference) {
        try {
            String path = new URL(reference).getPath();
            int dot = path.lastIndexOf('.');
            if (dot >= 0 && dot < path.length() - 1) {
                String extension = path.substring(dot).toLowerCase(Locale.ROOT);
                if (extension.matches("\\.[a-z0-9]{1,5}")) {
                    return extension;
                }
            }
        } catch (IOException ignored) {
            // URL 非法时交给连接阶段报错，这里只负责扩展名兜底
        }
        return DEFAULT_EXTENSION;
    }

    private static void deleteQuietly(Path target) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException ignored) {
            // 半成品清理失败不影响主流程
        }
    }
}
