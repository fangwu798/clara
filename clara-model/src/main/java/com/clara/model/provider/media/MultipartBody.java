package com.clara.model.provider.media;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 构建多部分 HTTP 请求体。
 */
public final class MultipartBody {
    private final String boundary;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    public MultipartBody(String boundary) {
        if (boundary == null || !boundary.matches("[A-Za-z0-9_-]{16,70}")) {
            throw new IllegalArgumentException("invalid multipart boundary");
        }
        this.boundary = boundary;
    }

    public MultipartBody text(String name, String value) {
        if (value == null) return this;
        header(name, null, null);
        write(value.getBytes(StandardCharsets.UTF_8));
        crlf();
        return this;
    }

    public MultipartBody file(String name, String fileName, String mimeType, byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("file value must not be null");
        }
        header(name, safeToken(fileName), safeToken(mimeType));
        write(value);
        crlf();
        return this;
    }

    public String contentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    public byte[] finish() {
        ascii("--" + boundary + "--\r\n");
        return output.toByteArray();
    }

    private void header(String name, String fileName, String mimeType) {
        String safeName = safeToken(name);
        ascii("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + safeName + "\"");
        if (fileName != null) ascii("; filename=\"" + fileName + "\"");
        ascii("\r\n");
        if (mimeType != null) ascii("Content-Type: " + mimeType + "\r\n");
        ascii("\r\n");
    }

    private static String safeToken(String value) {
        if (value == null || value.isEmpty() || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('"') >= 0) {
            throw new IllegalArgumentException("unsafe multipart token");
        }
        return value;
    }

    private void ascii(String value) {
        write(value.getBytes(StandardCharsets.US_ASCII));
    }

    private void crlf() {
        ascii("\r\n");
    }

    private void write(byte[] value) {
        try {
            output.write(value);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
