package com.clara.media;

import com.clara.config.ConfigLocator;
import com.clara.utils.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Materializes image generation results returned as Base64 into local files.
 *
 * <p>OpenAI gpt-image-1 always returns {@code b64_json}, while Volcengine returns
 * URLs. Keeping a multi-megabyte Base64 payload in workflow outputs would inflate
 * logs and model context, so Base64 references are decoded into
 * {@code <workspace>/.clara/media/images} and replaced with absolute file paths.
 * HTTP(S) URLs are returned unchanged and are not downloaded here.
 */
public final class ImageStore {

    /** Per-file limit; aligned with the provider response body limit. */
    public static final long DEFAULT_MAX_BYTES = 32L * 1024L * 1024L;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String DEFAULT_EXTENSION = ".png";
    private static final String DATA_PREFIX = "data:";

    private final Path directory;
    private final long maxBytes;

    /**
     * Creates a store under the current workspace.
     */
    public ImageStore() {
        this(ConfigLocator.workspaceRoot().resolve(".clara").resolve("media").resolve("images"),
                DEFAULT_MAX_BYTES);
    }

    /**
     * Creates a store with an explicit directory and size limit.
     *
     * @param directory target directory, not null
     * @param maxBytes  maximum decoded size of one image, positive
     */
    public ImageStore(Path directory, long maxBytes) {
        if (directory == null || maxBytes <= 0) {
            throw new IllegalArgumentException("image store settings must be positive");
        }
        this.directory = directory;
        this.maxBytes = maxBytes;
    }

    /**
     * Replaces Base64 image references with local file paths.
     *
     * @param references image references in URL, data URI or raw Base64 form
     * @return references where Base64 payloads are replaced by absolute paths
     * @throws IllegalStateException when a Base64 payload cannot be decoded or written
     */
    public List<String> materialize(List<String> references) {
        if (references == null || references.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> materialized = new ArrayList<>(references.size());
        int index = 0;
        for (String reference : references) {
            if (StringUtils.isBlank(reference)) {
                continue;
            }
            index++;
            materialized.add(materializeOne(reference.trim(), index));
        }
        return materialized;
    }

    private String materializeOne(String reference, int index) {
        if (isHttpUrl(reference) || isExistingFile(reference)) {
            return reference;
        }
        String encoded = reference;
        String extension = DEFAULT_EXTENSION;
        if (reference.startsWith(DATA_PREFIX)) {
            int comma = reference.indexOf(',');
            if (comma < 0 || !reference.substring(0, comma).toLowerCase(Locale.ROOT).contains(";base64")) {
                return reference;
            }
            String metadata = reference.substring(DATA_PREFIX.length(), comma);
            int semicolon = metadata.indexOf(';');
            extension = extensionFor(semicolon < 0 ? metadata : metadata.substring(0, semicolon));
            encoded = reference.substring(comma + 1);
        } else if (!looksLikeBase64(reference)) {
            return reference;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded.replace("\r", "").replace("\n", ""));
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("image Base64 decode failed: " + failure.getMessage(), failure);
        }
        if (bytes.length > maxBytes) {
            throw new IllegalStateException("image exceeds size limit: " + bytes.length + " bytes");
        }
        try {
            Files.createDirectories(directory);
            Path target = directory.resolve(fileName(index, extension));
            Files.write(target, bytes);
            return target.toAbsolutePath().toString();
        } catch (IOException failure) {
            throw new IllegalStateException("saving image failed: " + failure.getMessage(), failure);
        }
    }

    private static boolean isExistingFile(String value) {
        if (value.length() >= 512) {
            return false;
        }
        try {
            return Files.isRegularFile(java.nio.file.Paths.get(value));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isHttpUrl(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
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

    private static String extensionFor(String mimeType) {
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if (mime.contains("jpeg") || mime.contains("jpg")) {
            return ".jpg";
        }
        if (mime.contains("webp")) {
            return ".webp";
        }
        return DEFAULT_EXTENSION;
    }

    private static String fileName(int index, String extension) {
        return "clara-image-" + LocalDateTime.now().format(STAMP) + "-"
                + UUID.randomUUID().toString().substring(0, 8) + "-" + index + extension;
    }
}