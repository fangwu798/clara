package com.clara.kernel.message.provider;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link ImageSource} 的单元测试：data URI、裸 Base64、本地文件、HTTP 下载与失败原因。
 *
 * <p>图片编辑依赖参考图能被稳定解析；解析失败时必须给出可读原因，因此这里同时覆盖成功与异常路径。
 */
public class ImageSourceTest {

    private static final byte[] PNG_BYTES = new byte[] {1, 2, 3, 4, 5};

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String dataUri(String type, byte[] bytes) {
        return "data:" + type + ";base64," + base64(bytes);
    }

    @Test
    public void detectsReferenceKinds() {
        assertTrue(ImageSource.isDataUri("DATA:image/png;base64,AAA"));
        assertFalse(ImageSource.isDataUri("image/png"));
        assertFalse(ImageSource.isDataUri(null));

        assertTrue(ImageSource.isHttpUrl("http://example.com/a.png"));
        assertTrue(ImageSource.isHttpUrl("HTTPS://example.com/a.png"));
        assertFalse(ImageSource.isHttpUrl("ftp://example.com/a.png"));
        assertFalse(ImageSource.isHttpUrl(null));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBlankReference() {
        ImageSource.read("   ", 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNullReference() {
        ImageSource.read(null, 1);
    }

    @Test
    public void readsDataUriAndRoundTripsToDataUri() {
        ImageSource source = ImageSource.read(dataUri("image/png", PNG_BYTES), 3);

        assertArrayEquals(PNG_BYTES, source.getBytes());
        assertEquals("image/png", source.getContentType());
        assertEquals("reference-3.png", source.getFileName());
        assertEquals("data:image/png;base64," + base64(PNG_BYTES), source.toDataUri());
    }

    @Test
    public void dataUriIgnoresCharsetAndLowercasesType() {
        ImageSource source = ImageSource.read(
                "data:IMAGE/JPEG;charset=utf-8;base64," + base64(PNG_BYTES), 1);

        assertEquals("image/jpeg", source.getContentType());
        assertEquals("reference-1.jpg", source.getFileName());
    }

    @Test
    public void dataUriWithoutTypeFallsBackToPng() {
        ImageSource source = ImageSource.read("data:;base64," + base64(PNG_BYTES), 2);

        assertEquals("image/png", source.getContentType());
        assertEquals("reference-2.png", source.getFileName());
        assertArrayEquals(PNG_BYTES, source.getBytes());
    }

    @Test
    public void dataUriDecodeToleratesLineBreaks() {
        String encoded = base64(PNG_BYTES);
        String wrapped = encoded.substring(0, 4) + "\r\n" + encoded.substring(4);

        ImageSource source = ImageSource.read("data:image/png;base64," + wrapped, 1);

        assertArrayEquals(PNG_BYTES, source.getBytes());
    }

    @Test
    public void dataUriWithoutCommaIsRejected() {
        assertFailure("data:image/png;base64" + base64(PNG_BYTES), "缺少逗号");
    }

    @Test
    public void dataUriWithoutBase64MarkerIsRejected() {
        assertFailure("data:image/png," + base64(PNG_BYTES), "仅支持 base64");
    }

    @Test
    public void invalidBase64IsRejectedWithReason() {
        assertFailure("data:image/png;base64,!!!!", "Base64 解码失败");
    }

    @Test
    public void readsBareBase64WhenLongEnough() {
        byte[] payload = new byte[60];
        ImageSource source = ImageSource.read(base64(payload), 4);

        assertArrayEquals(payload, source.getBytes());
        assertEquals("image/png", source.getContentType());
        assertEquals("reference-4.png", source.getFileName());
    }

    @Test
    public void shortBase64LikeValueIsTreatedAsPath() {
        assertFailure("AAAA", "不存在或不是文件");
    }

    @Test
    public void readsLocalFilesAndInfersContentTypeByExtension() throws IOException {
        assertLocalFile("sample.jpg", "image/jpeg");
        assertLocalFile("sample.jpeg", "image/jpeg");
        assertLocalFile("sample.webp", "image/webp");
        assertLocalFile("sample.gif", "image/gif");
        assertLocalFile("sample.png", "image/png");
        assertLocalFile("sample.unknownext", "image/png");
    }

    private void assertLocalFile(String name, String expectedType) throws IOException {
        File file = folder.newFile(name);
        Files.write(file.toPath(), PNG_BYTES);

        ImageSource source = ImageSource.read(file.getAbsolutePath(), 1);

        assertArrayEquals(PNG_BYTES, source.getBytes());
        assertEquals(expectedType, source.getContentType());
        assertEquals(name, source.getFileName());
    }

    @Test
    public void missingFileReportsReadableReason() {
        String path = new File(folder.getRoot(), "missing.png").getAbsolutePath();
        assertFailure(path, "不存在或不是文件");
    }

    @Test
    public void invalidPathIsReported() {
        assertFailure("bad\u0000name", "无法解析");
    }

    @Test
    public void getBytesReturnsDefensiveCopy() {
        ImageSource source = ImageSource.read(dataUri("image/png", PNG_BYTES), 1);

        byte[] copy = source.getBytes();
        copy[0] = 99;

        assertArrayEquals(PNG_BYTES, source.getBytes());
    }

    @Test
    public void downloadsImageFromHttpAndUsesUrlFileName() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            byte[] body = PNG_BYTES;
            server.createContext("/assets/photo.png", exchange -> respond(exchange, 200,
                    "image/jpeg; charset=utf-8", body));
            server.start();
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/assets/photo.png";

            ImageSource source = ImageSource.read(url, 1);

            assertArrayEquals(body, source.getBytes());
            assertEquals("image/jpeg", source.getContentType());
            assertEquals("photo.png", source.getFileName());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void httpDownloadWithoutTypeOrFileNameFallsBackToDefaults() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/download", exchange -> respond(exchange, 200,
                    "application/octet-stream", PNG_BYTES));
            server.start();
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/download";

            ImageSource source = ImageSource.read(url, 7);

            assertEquals("image/png", source.getContentType());
            assertEquals("reference-7.png", source.getFileName());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void nonSuccessHttpStatusIsReported() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/missing.png", exchange -> respond(exchange, 404, "text/plain", new byte[0]));
            server.start();
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/missing.png";

            assertFailure(url, "HTTP 404");
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void malformedUrlIsWrapped() {
        assertFailure("http://", "下载参考图失败");
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        if (contentType != null) {
            exchange.getResponseHeaders().add("Content-Type", contentType);
        }
        if (body.length == 0) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }
        exchange.close();
    }

    private static void assertFailure(String reference, String expectedSnippet) {
        try {
            ImageSource.read(reference, 1);
            throw new AssertionError("expected IllegalStateException for " + reference);
        } catch (IllegalStateException exception) {
            String message = exception.getMessage();
            assertTrue("unexpected message: " + message,
                    message != null && message.contains(expectedSnippet));
        }
    }
}