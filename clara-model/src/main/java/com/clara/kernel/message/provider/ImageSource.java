package com.clara.kernel.message.provider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Locale;

/**
 * 单张参考图的字节内容与 multipart 元信息。
 *
 * <p>OpenAI 图片编辑接口只接收 multipart 文件，因此参考图必须先落成字节。本类统一支持本地文件、
 * HTTP(S) URL、{@code data:image/...;base64,...} 与裸 Base64 四种引用形式，并把解析失败转成带可读
 * 原因的业务异常，避免上游只看到「媒体生成结果为空」。
 */
public final class ImageSource {

    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final int BUFFER_SIZE = 8192;
    private static final String DATA_PREFIX = "data:";
    private static final String DEFAULT_CONTENT_TYPE = "image/png";

    private final byte[] bytes;
    private final String fileName;
    private final String contentType;

    private ImageSource(byte[] bytes, String fileName, String contentType) {
        this.bytes = bytes;
        this.fileName = fileName;
        this.contentType = contentType;
    }

    /**
     * 解析一个参考图引用。
     *
     * @param reference 本地路径、HTTP(S) URL、data URI 或裸 Base64，不可为空白
     * @param index     同一请求内的序号（从 1 开始），用于生成缺省文件名
     * @return 参考图字节与元信息，非 null
     * @throws IllegalStateException 文件不存在、下载失败、编码非法或超过大小上限时
     */
    public static ImageSource read(String reference, int index) {
        if (reference == null || reference.trim().isEmpty()) {
            throw new IllegalArgumentException("image reference must not be blank");
        }
        String value = reference.trim();
        if (isDataUri(value)) {
            return fromDataUri(value, index);
        }
        if (isHttpUrl(value)) {
            return fromUrl(value, index);
        }
        return fromFile(value, index);
    }

    /**
     * 是否为 data URI 引用。
     *
     * @param value 待判断的引用，可为 null
     * @return 以 {@code data:} 开头时返回 true
     */
    public static boolean isDataUri(String value) {
        return value != null && value.regionMatches(true, 0, DATA_PREFIX, 0, DATA_PREFIX.length());
    }

    /**
     * 是否为 HTTP(S) 引用。
     *
     * @param value 待判断的引用，可为 null
     * @return 以 http:// 或 https:// 开头时返回 true
     */
    public static boolean isHttpUrl(String value) {
        if (value == null) {
            return false;
        }
        return value.regionMatches(true, 0, "http://", 0, 7)
                || value.regionMatches(true, 0, "https://", 0, 8);
    }

    /**
     * 转成 data URI，供只接受 URL / data URI 的平台使用。
     *
     * @return {@code data:<类型>;base64,<编码>}，非 null
     */
    public String toDataUri() {
        return DATA_PREFIX + contentType + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * 图片字节副本。
     *
     * @return 非 null
     */
    public byte[] getBytes() {
        return bytes.clone();
    }

    /**
     * 文件名（带扩展名），用于 multipart 的 filename 参数。
     *
     * @return 非空白
     */
    public String getFileName() {
        return fileName;
    }

    /**
     * 图片媒体类型，用于 multipart 分段的 Content-Type。
     *
     * @return 形如 {@code image/png}，非空白
     */
    public String getContentType() {
        return contentType;
    }

    private static ImageSource fromDataUri(String value, int index) {
        int comma = value.indexOf(',');
        if (comma < 0) {
            throw new IllegalStateException("参考图 data URI 缺少逗号分隔");
        }
        String metadata = value.substring(DATA_PREFIX.length(), comma);
        if (!metadata.toLowerCase(Locale.ROOT).contains(";base64")) {
            throw new IllegalStateException("参考图 data URI 仅支持 base64 编码");
        }
        int semicolon = metadata.indexOf(';');
        String type = semicolon < 0 ? metadata : metadata.substring(0, semicolon);
        String contentType = type.isEmpty() ? DEFAULT_CONTENT_TYPE : type.toLowerCase(Locale.ROOT);
        byte[] decoded = decode(value.substring(comma + 1));
        return new ImageSource(decoded, defaultFileName(index, contentType), contentType);
    }

    private static ImageSource fromBase64(String value, int index) {
        byte[] decoded = decode(value);
        return new ImageSource(decoded, defaultFileName(index, DEFAULT_CONTENT_TYPE), DEFAULT_CONTENT_TYPE);
    }

    private static ImageSource fromFile(String value, int index) {
        if (looksLikeBase64(value)) {
            return fromBase64(value, index);
        }
        Path file;
        try {
            file = Paths.get(value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("参考图引用无法解析：" + shorten(value), exception);
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("参考图不存在或不是文件：" + shorten(value));
        }
        try {
            long size = Files.size(file);
            if (size > MAX_BYTES) {
                throw new IllegalStateException("参考图超过大小上限：" + size + " bytes");
            }
            byte[] bytes = Files.readAllBytes(file);
            String name = file.getFileName() == null ? defaultFileName(index, DEFAULT_CONTENT_TYPE)
                    : file.getFileName().toString();
            return new ImageSource(bytes, name, contentTypeFor(name));
        } catch (IOException exception) {
            throw new IllegalStateException("读取参考图失败：" + exception.getMessage(), exception);
        }
    }

    private static ImageSource fromUrl(String value, int index) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(value).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "clara-image-edit/1.0");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("下载参考图失败：HTTP " + status + "（" + shorten(value) + "）");
            }
            String contentType = normalizeContentType(connection.getContentType());
            try (InputStream input = connection.getInputStream()) {
                byte[] bytes = readLimited(input);
                String type = contentType == null ? DEFAULT_CONTENT_TYPE : contentType;
                return new ImageSource(bytes, fileNameFromUrl(value, index, type), type);
            }
        } catch (IOException exception) {
            throw downloadFailure(exception);
        } catch (RuntimeException exception) {
            throw downloadFailure(exception);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 把下载参考图阶段的失败统一包装成业务异常。
     *
     * <p>部分 JDK 实现不会在构造 {@link URL} 时拒绝缺少主机名的地址，而是延迟到
     * {@code getResponseCode()} 才抛出 {@link IllegalArgumentException} 之类的运行时异常，
     * 因此这里必须把 {@link RuntimeException} 一并包装，避免上游只看到原始异常。
     * 已经带有可读原因的 {@link IllegalStateException}（如 HTTP 状态码非 2xx、超过大小上限）
     * 直接向上抛出，不再二次包装。</p>
     *
     * @param cause 底层失败原因，不可为 null
     * @return 可直接抛出的业务异常
     */
    private static IllegalStateException downloadFailure(Throwable cause) {
        if (cause instanceof IllegalStateException) {
            return (IllegalStateException) cause;
        }
        return new IllegalStateException("下载参考图失败：" + cause.getMessage(), cause);
    }

    private static byte[] decode(String encoded) {
        try {
            return Base64.getDecoder().decode(encoded.replace("\r", "").replace("\n", ""));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("参考图 Base64 解码失败：" + exception.getMessage(), exception);
        }
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[BUFFER_SIZE];
        int total = 0;
        int read;
        while ((read = input.read(chunk)) >= 0) {
            total += read;
            if (total > MAX_BYTES) {
                throw new IllegalStateException("参考图超过大小上限：" + MAX_BYTES + " bytes");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static String normalizeContentType(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        int semicolon = value.indexOf(';');
        String type = (semicolon < 0 ? value : value.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
        return type.startsWith("image/") ? type : null;
    }

    private static String fileNameFromUrl(String value, int index, String contentType) {
        try {
            String urlPath = new URL(value).getPath();
            int slash = urlPath.lastIndexOf('/');
            String name = slash < 0 ? urlPath : urlPath.substring(slash + 1);
            if (!name.isEmpty() && name.lastIndexOf('.') > 0) {
                return name;
            }
        } catch (IOException exception) {
            return defaultFileName(index, contentType);
        }
        return defaultFileName(index, contentType);
    }

    private static String defaultFileName(int index, String contentType) {
        return "reference-" + index + extensionFor(contentType);
    }

    private static String extensionFor(String contentType) {
        if ("image/jpeg".equals(contentType)) {
            return ".jpg";
        }
        if ("image/webp".equals(contentType)) {
            return ".webp";
        }
        if ("image/gif".equals(contentType)) {
            return ".gif";
        }
        return ".png";
    }

    private static String contentTypeFor(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        return DEFAULT_CONTENT_TYPE;
    }

    private static boolean looksLikeBase64(String value) {
        if (value.length() < 64) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            boolean valid = current >= 'A' && current <= 'Z'
                    || current >= 'a' && current <= 'z'
                    || current >= '0' && current <= '9'
                    || current == '+' || current == '/' || current == '=' || current == '\r' || current == '\n';
            if (!valid) {
                return false;
            }
        }
        return true;
    }

    private static String shorten(String value) {
        return value.length() <= 120 ? value : value.substring(0, 117) + "...";
    }
}
