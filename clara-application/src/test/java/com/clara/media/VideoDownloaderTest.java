package com.clara.media;

import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link VideoDownloader} 单元测试：正常下载、跳转、扩展名推断、HTTP 错误与大小上限。
 */
public class VideoDownloaderTest {

    private static final byte[] VIDEO = "fake-video-bytes".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private Path directory;
    private String baseUrl;

    @Before
    public void setUp() throws IOException {
        directory = Files.createTempDirectory("clara-video-");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/clip.mp4", exchange -> respond(exchange, 200, VIDEO));
        server.createContext("/redirect.mp4", exchange -> {
            exchange.getResponseHeaders().add("Location", "/clip.mp4");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/missing.mp4", exchange -> respond(exchange, 404, new byte[0]));
        server.createContext("/large.mp4", exchange -> respond(exchange, 200, new byte[32]));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void tearDown() throws IOException {
        server.stop(0);
        try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
            files.sorted((left, right) -> right.getNameCount() - left.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // 临时目录清理失败不影响测试结果
                        }
                    });
        }
    }

    @Test
    public void downloadsVideoAndKeepsExtension() {
        VideoDownloader downloader = downloader(DEFAULT_MAX);

        List<Path> files = downloader.download(Collections.singletonList(baseUrl + "/clip.mp4"));

        assertEquals(1, files.size());
        assertTrue(files.get(0).getFileName().toString().endsWith(".mp4"));
        assertArrayEquals(VIDEO, read(files.get(0)));
    }

    @Test
    public void followsRedirectAndDefaultsToMp4() {
        VideoDownloader downloader = downloader(DEFAULT_MAX);

        List<Path> files = downloader.download(Collections.singletonList(baseUrl + "/redirect.mp4"));

        assertEquals(1, files.size());
        assertTrue(files.get(0).getFileName().toString().endsWith(".mp4"));
        assertArrayEquals(VIDEO, read(files.get(0)));
    }

    @Test
    public void failsOnHttpError() {
        VideoDownloader downloader = downloader(DEFAULT_MAX);

        try {
            downloader.download(Collections.singletonList(baseUrl + "/missing.mp4"));
            fail("expected failure");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("404"));
        }
    }

    @Test
    public void failsWhenFileExceedsLimit() {
        VideoDownloader downloader = downloader(4L);

        try {
            downloader.download(Collections.singletonList(baseUrl + "/large.mp4"));
            fail("expected failure");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("上限"));
        }
    }

    private static final long DEFAULT_MAX = 1024L * 1024L;

    private VideoDownloader downloader(long maxBytes) {
        return new VideoDownloader(directory, 2_000, 5_000, maxBytes);
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, byte[] body)
            throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
