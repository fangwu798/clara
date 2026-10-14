package com.clara.media.filesystem;

import com.clara.exception.ClaraError;
import com.clara.exception.ClaraException;
import com.clara.exception.ErrorCategory;
import com.clara.exception.ErrorCodes;
import com.clara.media.MediaAsset;
import com.clara.media.MediaInput;
import com.clara.media.MediaStore;
import com.clara.media.MediaType;
import com.clara.media.MediaUpload;
import com.clara.media.MediaValidation;
import com.clara.media.MediaValidator;
import com.clara.media.validation.DefaultMediaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 在工作区内以内容摘要为资产 ID，原子保存媒体内容和清单，并执行租户隔离、校验及过期清理。
 *
 * <p>常规用法：普通场景使用单参构造函数；需自定义校验器（如注入元数据探针或内容安全检查）时使用三参构造函数；
 * 通过 {@link #put} 写入，通过 {@code get/open/delete} 访问，定期调用 {@link #cleanupExpired}。
 * 最后调用 {@link #close()} 释放目录锁。
 *
 * <p>注意：同一资产目录一次只能由一个实例持有。{@link #open} 返回的流由调用方关闭；资产按租户隔离且
 * 读取时会复核清单、大小和 SHA-256，禁止绕过本类直接修改 {@code .clara/assets} 内容。
 */
public final class FileSystemMediaStore implements MediaStore, Closeable {
    private static final int FORMAT_VERSION = 1;
    private final FileSystemMediaStoreConfig config;
    private final MediaValidator validator;
    private final ObjectMapper json;
    private final Path assetsRoot;
    private final FileChannel lockChannel;
    private final FileLock lock;

    public FileSystemMediaStore(FileSystemMediaStoreConfig config) {
        this(config, new DefaultMediaValidator(config == null ? null : config.getValidationPolicy()), new ObjectMapper());
    }

    public FileSystemMediaStore(FileSystemMediaStoreConfig config, MediaValidator validator, ObjectMapper json) {
        if (config == null || validator == null || json == null) {
            throw new IllegalArgumentException("dependencies must not be null");
        }
        this.config = config;
        this.validator = validator;
        this.json = json;
        this.assetsRoot = config.getWorkspaceRoot().resolve(".clara").resolve("assets").normalize();
        FileChannel channel = null;
        FileLock acquired = null;
        try {
            Files.createDirectories(assetsRoot.resolve(".tmp"));
            channel = FileChannel.open(assetsRoot.resolve("lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            acquired = channel.tryLock();
            if (acquired == null) locked();
        } catch (ClaraException e) {
            closeQuietly(channel);
            throw e;
        } catch (Exception e) {
            closeQuietly(channel);
            locked();
        }
        this.lockChannel = channel;
        this.lock = acquired;
    }

    public synchronized MediaAsset put(MediaUpload upload) {
        if (upload == null) {
            throw new IllegalArgumentException("upload must not be null");
        }
        Path stage = assetsRoot.resolve(".tmp").resolve(UUID.randomUUID().toString());
        try {
            Files.createDirectory(stage);
            Path content = stage.resolve("content");
            DigestAndSize digest = copy(upload.getInput().open(), content, config.getMaxFileBytes());
            String assetId = digest.sha256;
            Path destination = assetDirectory(upload.getTenantId(), assetId);
            long existingSize = Files.isDirectory(destination) ? readManifest(destination, upload.getTenantId(), assetId).size : 0;
            long usage = tenantUsage(upload.getTenantId()) - existingSize;
            MediaInput stagedInput = new MediaInput() {
                public InputStream open() throws IOException {
                    return Files.newInputStream(content);
                }
            };
            MediaValidation validation = validator.validate(upload, stagedInput, digest.size, usage);
            long now = System.currentTimeMillis();
            long maximumExpiry = safeAdd(now, config.getRetentionMillis());
            Long expiry = upload.getExpiresAt() == null ? Long.valueOf(maximumExpiry) : upload.getExpiresAt();
            if (expiry.longValue() <= now || expiry.longValue() > maximumExpiry) invalid();
            if (Files.isDirectory(destination)) {
                deleteTree(stage);
                return get(upload.getTenantId(), assetId);
            }
            Manifest manifest = Manifest.from(upload, validation, assetId, digest.size, now, expiry);
            json.writeValue(stage.resolve("manifest.json").toFile(), manifest);
            Files.createDirectories(destination.getParent());
            Files.move(stage, destination, StandardCopyOption.ATOMIC_MOVE);
            return manifest.toAsset();
        } catch (ClaraException e) {
            deleteTreeQuietly(stage);
            throw e;
        } catch (IOException e) {
            deleteTreeQuietly(stage);
            ioError();
            return null;
        }
    }

    public synchronized MediaAsset get(String tenantId, String assetId) {
        validateIdentity(tenantId, assetId);
        Path directory = assetDirectory(tenantId, assetId);
        if (!Files.isDirectory(directory)) notFound();
        try {
            Manifest manifest = readManifest(directory, tenantId, assetId);
            if (manifest.expiresAt != null && manifest.expiresAt.longValue() <= System.currentTimeMillis()) {
                deleteTree(directory);
                notFound();
            }
            Path content = directory.resolve("content");
            DigestAndSize actual = digest(content);
            if (actual.size != manifest.size || !actual.sha256.equals(manifest.sha256)) ioError();
            return manifest.toAsset();
        } catch (ClaraException e) {
            throw e;
        } catch (IOException e) {
            ioError();
            return null;
        }
    }

    public synchronized InputStream open(String tenantId, String assetId) {
        get(tenantId, assetId);
        try {
            return new BufferedInputStream(Files.newInputStream(assetDirectory(tenantId, assetId).resolve("content")));
        } catch (IOException e) {
            ioError();
            return null;
        }
    }

    public synchronized void delete(String tenantId, String assetId) {
        validateIdentity(tenantId, assetId);
        Path directory = assetDirectory(tenantId, assetId);
        if (!Files.isDirectory(directory)) notFound();
        try {
            readManifest(directory, tenantId, assetId);
            deleteTree(directory);
        } catch (ClaraException e) {
            throw e;
        } catch (IOException e) {
            ioError();
        }
    }

    public synchronized int cleanupExpired(long now) {
        int removed = 0;
        try {
            DirectoryStream<Path> tenants = Files.newDirectoryStream(assetsRoot, new DirectoryStream.Filter<Path>() {
                public boolean accept(Path entry) {
                    return Files.isDirectory(entry) && !entry.getFileName().toString().startsWith(".");
                }
            });
            try {
                for (Path tenant : tenants) {
                    DirectoryStream<Path> assets = Files.newDirectoryStream(tenant);
                    try {
                        for (Path asset : assets) {
                            try {
                                Manifest manifest = json.readValue(asset.resolve("manifest.json").toFile(), Manifest.class);
                                if (manifest.expiresAt != null && manifest.expiresAt.longValue() <= now) {
                                    deleteTree(asset);
                                    removed++;
                                }
                            } catch (IOException corruptOrRacing) {
                                ioError();
                            }
                        }
                    } finally {
                        assets.close();
                    }
                }
            } finally {
                tenants.close();
            }
            return removed;
        } catch (ClaraException e) {
            throw e;
        } catch (IOException e) {
            ioError();
            return 0;
        }
    }

    public void close() throws IOException {
        try {
            if (lock != null && lock.isValid()) lock.release();
        } finally {
            lockChannel.close();
        }
    }

    private Manifest readManifest(Path directory, String tenantId, String assetId) throws IOException {
        Manifest manifest = json.readValue(directory.resolve("manifest.json").toFile(), Manifest.class);
        if (manifest.formatVersion != FORMAT_VERSION || !assetId.equals(manifest.assetId)
                || !tenantId.equals(manifest.ownerTenantId) || !assetId.equals(manifest.sha256)) ioError();
        return manifest;
    }

    private long tenantUsage(String tenantId) throws IOException {
        Path directory = tenantDirectory(tenantId);
        if (!Files.isDirectory(directory)) return 0;
        long total = 0;
        DirectoryStream<Path> stream = Files.newDirectoryStream(directory);
        try {
            for (Path asset : stream)
                if (Files.isDirectory(asset)) {
                    Manifest manifest = json.readValue(asset.resolve("manifest.json").toFile(), Manifest.class);
                    if (!tenantId.equals(manifest.ownerTenantId)) denied();
                    if (Long.MAX_VALUE - total < manifest.size) resourceLimit();
                    total += manifest.size;
                }
        } finally {
            stream.close();
        }
        return total;
    }

    private Path tenantDirectory(String tenantId) {
        return assetsRoot.resolve(sha256(tenantId.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private Path assetDirectory(String tenantId, String assetId) {
        return tenantDirectory(tenantId).resolve(assetId);
    }

    private static void validateIdentity(String tenantId, String assetId) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (assetId == null || !assetId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid assetId");
        }
    }

    private static DigestAndSize copy(InputStream source, Path target, long limit) throws IOException {
        if (source == null) {
            throw new IOException("media input returned null");
        }
        MessageDigest digest = sha256Digest();
        long size = 0;
        InputStream input = new BufferedInputStream(source);
        OutputStream output = new BufferedOutputStream(Files.newOutputStream(target));
        try {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count == 0) continue;
                if (size > limit - count) resourceLimit();
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
                size += count;
            }
        } finally {
            try {
                input.close();
            } finally {
                output.close();
            }
        }
        return new DigestAndSize(hex(digest.digest()), size);
    }

    private static DigestAndSize digest(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        long size = 0;
        InputStream input = new BufferedInputStream(Files.newInputStream(path));
        try {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    digest.update(buffer, 0, count);
                    size += count;
                }
            }
        } finally {
            input.close();
        }
        return new DigestAndSize(hex(digest.digest()), size);
    }

    private static String sha256(byte[] value) {
        MessageDigest digest = sha256Digest();
        return hex(digest.digest(value));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] value) {
        StringBuilder text = new StringBuilder(value.length * 2);
        for (byte b : value) text.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return text.toString();
    }

    private static long safeAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        if (Files.isDirectory(path)) {
            DirectoryStream<Path> stream = Files.newDirectoryStream(path);
            try {
                for (Path child : stream) deleteTree(child);
            } finally {
                stream.close();
            }
        }
        Files.deleteIfExists(path);
    }

    private static void deleteTreeQuietly(Path path) {
        try {
            deleteTree(path);
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable != null) try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    private static void notFound() {
        fail(ErrorCodes.MEDIA_NOT_FOUND, ErrorCategory.VALIDATION);
    }

    private static void invalid() {
        fail(ErrorCodes.MEDIA_VALIDATION_FAILED, ErrorCategory.VALIDATION);
    }

    private static void denied() {
        fail(ErrorCodes.MEDIA_ACCESS_DENIED, ErrorCategory.AUTHORIZATION);
    }

    private static void locked() {
        fail(ErrorCodes.MEDIA_STORE_LOCKED, ErrorCategory.CONFLICT);
    }

    private static void resourceLimit() {
        fail(ErrorCodes.MEDIA_RESOURCE_LIMIT, ErrorCategory.RESOURCE_LIMIT);
    }

    private static void ioError() {
        fail(ErrorCodes.MEDIA_STORE_IO_ERROR, ErrorCategory.INTERNAL);
    }

    private static void fail(String code, ErrorCategory category) {
        throw new ClaraException(new ClaraError(code, category, code, false, null, null, null, null, Collections.<String, String>emptyMap()));
    }

    /**
     * 表示。
     */
    private static final class DigestAndSize {
        private final String sha256;
        private final long size;

        private DigestAndSize(String sha256, long size) {
            this.sha256 = sha256;
            this.size = size;
        }
    }

    /**
     * 表示。
     */
    public static final class Manifest {
        public int formatVersion;
        public String assetId;
        public String mediaType;
        public String mimeType;
        public long size;
        public String sha256;
        public String storageUri;
        public String sourceUri;
        public Integer width;
        public Integer height;
        public Long durationMs;
        public String ownerTenantId;
        public long createdAt;
        public Long expiresAt;
        public Map<String, String> metadata;

        public Manifest() {
        }

        static Manifest from(MediaUpload upload, MediaValidation validation, String id, long size, long now, Long expiry) {
            Manifest value = new Manifest();
            value.formatVersion = FORMAT_VERSION;
            value.assetId = id;
            value.mediaType = upload.getMediaType().name();
            value.mimeType = validation.getDetectedMimeType();
            value.size = size;
            value.sha256 = id;
            value.storageUri = "clara-media:" + id;
            value.sourceUri = upload.getSourceUri();
            value.width = validation.getWidth();
            value.height = validation.getHeight();
            value.durationMs = validation.getDurationMs();
            value.ownerTenantId = upload.getTenantId();
            value.createdAt = now;
            value.expiresAt = expiry;
            value.metadata = upload.getMetadata();
            return value;
        }

        MediaAsset toAsset() {
            return new MediaAsset(assetId, MediaType.valueOf(mediaType), mimeType, size, sha256, storageUri, sourceUri, width, height, durationMs, ownerTenantId, createdAt, expiresAt, metadata);
        }
    }
}
