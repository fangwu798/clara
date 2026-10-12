package com.clara.media.asset;

import com.clara.media.MediaAsset;
import com.clara.media.MediaInput;
import com.clara.media.MediaStore;
import com.clara.media.MediaType;
import com.clara.media.MediaUpload;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;
import java.util.Collections;
import java.util.Locale;

/**
 * 媒体资产摄取器：把模型产出的图片引用（公网 URL / data URI / 原始 base64）落成可引用的媒体资产。
 *
 * <p>是什么、在哪一层：application 媒体层（{@code com.clara.media.asset}）的组件，把「图片生成结果引用」
 * 转成 {@link MediaUpload} 并写入 {@link MediaStore}，返回带 {@code assetId} 与 {@code storageUri} 的资产。
 *
 * <p>为什么需要：模型返回的图片 URL 通常短时效且不可控（如火山方舟 24 小时失效），落成本地受管资产后，
 * 工作流可以把稳定的资产引用写回执行作用域，供后续节点或落盘复用。
 *
 * <p>失败语义：引用为空、无法下载、解码失败或图片格式不受媒体存储支持时抛
 * {@link IllegalArgumentException}；下载或读取异常抛 {@link IllegalStateException}。
 */
public final class MediaAssetIngestor {

    /**
     * 单张图片的读取上限（字节），超出即拒绝，避免把超大响应读入内存。
     */
    private static final int MAX_BYTES = 64 * 1024 * 1024;

    /**
     * 建立连接超时（毫秒）。
     */
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    /**
     * 读取超时（毫秒）。
     */
    private static final int READ_TIMEOUT_MS = 30_000;

    /**
     * 错误信息中展示的引用前缀长度，避免把大段 base64 写入日志。
     */
    private static final int PREVIEW_CHARS = 32;

    private final MediaStore mediaStore;
    private final String tenantId;

    /**
     * 创建摄取器。
     *
     * @param mediaStore 媒体存储，不可为 null
     * @param tenantId   资产所属租户标识，不可为空白
     * @throws IllegalArgumentException 参数非法时
     */
    public MediaAssetIngestor(MediaStore mediaStore, String tenantId) {
        if (mediaStore == null) {
            throw new IllegalArgumentException("mediaStore must not be null");
        }
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        this.mediaStore = mediaStore;
        this.tenantId = tenantId;
    }

    /**
     * 摄取一个图片引用为媒体资产。
     *
     * @param reference 图片引用（公网 URL / {@code data:} URI / 原始 base64），不可为空白
     * @return 已落库的媒体资产，非 null
     * @throws IllegalArgumentException 引用非法、格式不受支持时
     * @throws IllegalStateException    下载或读取失败时
     */
    public MediaAsset ingest(String reference) {
        if (reference == null || reference.trim().isEmpty()) {
            throw new IllegalArgumentException("reference must not be blank");
        }
        String value = reference.trim();
        byte[] bytes;
        String mime;
        String sourceUri = null;
        if (isHttpUrl(value)) {
            Downloaded downloaded = download(value);
            bytes = downloaded.bytes;
            mime = downloaded.mime == null ? detectMime(bytes) : normalizeMime(downloaded.mime);
            sourceUri = value;
        } else if (value.startsWith("data:")) {
            int comma = value.indexOf(',');
            if (comma < 0) {
                throw new IllegalArgumentException("data URI 缺少逗号分隔的载荷");
            }
            String header = value.substring("data:".length(), comma);
            String declared = header.endsWith(";base64") ? header.substring(0, header.length() - ";base64".length()) : header;
            bytes = decodeBase64(value.substring(comma + 1));
            mime = declared.trim().isEmpty() ? detectMime(bytes) : normalizeMime(declared);
        } else {
            bytes = decodeBase64(value);
            mime = detectMime(bytes);
        }
        if (mime == null) {
            throw new IllegalArgumentException("无法识别图片格式（引用前缀：" + preview(value) + "）");
        }
        MediaUpload upload = new MediaUpload(tenantId, MediaType.IMAGE, "generated" + extensionFor(mime), mime,
                sourceUri, null, null, null, null, Collections.<String, String>emptyMap(), inMemoryInput(bytes));
        return mediaStore.put(upload);
    }

    private static boolean isHttpUrl(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static Downloaded download(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "image/*");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("图片下载失败：HTTP " + status);
            }
            try (InputStream input = connection.getInputStream()) {
                return new Downloaded(readAll(input), connection.getContentType());
            }
        } catch (IOException failure) {
            throw new IllegalStateException("图片下载失败：" + failure.getMessage(), failure);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(chunk)) >= 0) {
            total += read;
            if (total > MAX_BYTES) {
                throw new IllegalStateException("图片超出大小上限：" + MAX_BYTES + " 字节");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static byte[] decodeBase64(String value) {
        try {
            return Base64.getMimeDecoder().decode(value);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("图片 base64 解码失败：" + failure.getMessage(), failure);
        }
    }

    private static String detectMime(byte[] bytes) {
        if (startsWith(bytes, new int[] {0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})) {
            return "image/png";
        }
        if (startsWith(bytes, new int[] {0xFF, 0xD8, 0xFF})) {
            return "image/jpeg";
        }
        if (startsWith(bytes, new int[] {'G', 'I', 'F', '8'})) {
            return "image/gif";
        }
        return null;
    }

    private static boolean startsWith(byte[] bytes, int[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if ((bytes[index] & 0xFF) != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static String extensionFor(String mime) {
        if ("image/png".equals(mime)) {
            return ".png";
        }
        if ("image/jpeg".equals(mime)) {
            return ".jpg";
        }
        if ("image/gif".equals(mime)) {
            return ".gif";
        }
        throw new IllegalArgumentException("媒体存储不支持该图片类型：" + mime);
    }

    private static String normalizeMime(String value) {
        int semicolon = value.indexOf(';');
        String mime = (semicolon < 0 ? value : value.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
        return mime.isEmpty() ? null : mime;
    }

    private static MediaInput inMemoryInput(final byte[] bytes) {
        return new MediaInput() {
            @Override
            public InputStream open() {
                return new ByteArrayInputStream(bytes);
            }
        };
    }

    private static String preview(String value) {
        return value.length() <= PREVIEW_CHARS ? value : value.substring(0, PREVIEW_CHARS) + "...";
    }

    /**
     * 下载结果：原始字节与响应声明的 MIME（可能为 null）。
     */
    private static final class Downloaded {

        private final byte[] bytes;
        private final String mime;

        Downloaded(byte[] bytes, String mime) {
            this.bytes = bytes;
            this.mime = mime;
        }
    }
}
