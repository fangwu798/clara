package com.clara.media.object;

import com.clara.kernel.ClaraError;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCategory;
import com.clara.kernel.ErrorCodes;
import com.clara.media.MediaAsset;
import com.clara.media.MediaInput;
import com.clara.media.MediaContentSafetyScanner;
import com.clara.media.MediaMetadataProbe;
import com.clara.media.MediaStore;
import com.clara.media.MediaUpload;
import com.clara.media.MediaValidation;
import com.clara.media.MediaValidator;
import com.clara.media.validation.DefaultMediaValidator;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 基于对象存储后端保存媒体资产，采用暂存、校验和条件发布流程保证内容完整性与租户配额。
 *
 * <p>常规用法：注入支持暂存和原子发布的 {@link ObjectMediaBackend}，通过 {@link #put} 写入资产，
 * 通过 {@code get/open/delete} 访问，并定期执行 {@link #cleanupExpired}；结束后调用 {@link #close()}。
 *
 * <p>注意：{@link #open} 返回的流由调用方关闭。后端必须正确实现 {@code publishIfAbsent} 和租户 usage，
 * 本类会验证键空间、摘要、大小及资产归属；暂存对象在成功或失败后都会尝试清理。
 */
public final class ObjectMediaStore implements MediaStore, Closeable {
    private final ObjectMediaStoreConfig config;
    private final ObjectMediaBackend backend;
    private final MediaValidator validator;
    private boolean closed;

    public ObjectMediaStore(ObjectMediaStoreConfig config, ObjectMediaBackend backend) {
        this(config, backend, new DefaultMediaValidator(config == null ? null : config.getValidationPolicy()));
    }

    public ObjectMediaStore(ObjectMediaStoreConfig config, ObjectMediaBackend backend,
                            List<MediaMetadataProbe> metadataProbes, MediaContentSafetyScanner safetyScanner) {
        this(config, backend, new DefaultMediaValidator(config == null ? null : config.getValidationPolicy(),
                metadataProbes, safetyScanner));
    }

    public ObjectMediaStore(ObjectMediaStoreConfig config, ObjectMediaBackend backend, MediaValidator validator) {
        if (config == null || backend == null || validator == null) {
            throw new IllegalArgumentException("dependencies must not be null");
        }
        this.config = config;
        this.backend = backend;
        this.validator = validator;
    }

    public synchronized MediaAsset put(MediaUpload upload) {
        ensureOpen();
        if (upload == null) {
            throw new IllegalArgumentException("upload must not be null");
        }
        String stagingKey = config.getNamespace() + "/staging/" + UUID.randomUUID().toString();
        boolean staged = false;
        try {
            InputStream source = upload.getInput().open();
            if (source == null) {
                throw new IOException("media input returned null");
            }
            StagedMediaObject stagedObject;
            try {
                stagedObject = backend.stage(stagingKey, source, config.getValidationPolicy().getMaxFileBytes());
            } finally {
                source.close();
            }
            staged = true;
            if (stagedObject == null || !stagingKey.equals(stagedObject.getKey())) backendFailure(false);
            DigestAndSize actual = digest(backend.open(stagingKey), config.getValidationPolicy().getMaxFileBytes());
            if (actual.size != stagedObject.getSize() || !actual.sha256.equals(stagedObject.getSha256()))
                backendFailure(false);
            String tenantPrefix = tenantPrefix(upload.getTenantId());
            String destinationKey = tenantPrefix + actual.sha256;
            ObjectMediaRecord existing = backend.head(destinationKey);
            long usage = backend.usage(tenantPrefix);
            if (existing != null) {
                verify(existing, upload.getTenantId(), actual.sha256, destinationKey);
                usage -= existing.getSize();
            }
            if (usage < 0) backendFailure(false);
            MediaInput stagedInput = new MediaInput() {
                public InputStream open() {
                    return backend.open(stagingKey);
                }
            };
            MediaValidation validation = validator.validate(upload, stagedInput, actual.size, usage);
            long now = System.currentTimeMillis();
            long maximumExpiry = safeAdd(now, config.getValidationPolicy().getRetentionMillis());
            Long expiry = upload.getExpiresAt() == null ? Long.valueOf(maximumExpiry) : upload.getExpiresAt();
            if (expiry.longValue() <= now || expiry.longValue() > maximumExpiry) invalid();
            MediaAsset asset = new MediaAsset(actual.sha256, upload.getMediaType(), validation.getDetectedMimeType(),
                    actual.size, actual.sha256, "clara-object:" + destinationKey, upload.getSourceUri(),
                    validation.getWidth(), validation.getHeight(), validation.getDurationMs(), upload.getTenantId(),
                    now, expiry, upload.getMetadata());
            ObjectMediaRecord published = backend.publishIfAbsent(stagingKey, destinationKey, tenantPrefix,
                    config.getValidationPolicy().getMaxTenantBytes(), asset);
            verify(published, upload.getTenantId(), actual.sha256, destinationKey);
            return published.getAsset();
        } catch (ObjectMediaBackendException e) {
            backendFailure(e);
            return null;
        } catch (ClaraException e) {
            throw e;
        } catch (IOException e) {
            backendFailure(true);
            return null;
        } catch (RuntimeException e) {
            backendFailure(false);
            return null;
        } finally {
            if (staged) deleteQuietly(stagingKey);
        }
    }

    public synchronized MediaAsset get(String tenantId, String assetId) {
        ensureOpen();
        validateIdentity(tenantId, assetId);
        String key = tenantPrefix(tenantId) + assetId;
        try {
            ObjectMediaRecord record = backend.head(key);
            if (record == null) notFound();
            verify(record, tenantId, assetId, key);
            MediaAsset asset = record.getAsset();
            if (asset.getExpiresAt() != null && asset.getExpiresAt().longValue() <= System.currentTimeMillis()) {
                backend.delete(key);
                notFound();
            }
            return asset;
        } catch (ObjectMediaBackendException e) {
            backendFailure(e);
            return null;
        } catch (ClaraException e) {
            throw e;
        } catch (RuntimeException e) {
            backendFailure(false);
            return null;
        }
    }

    public synchronized InputStream open(String tenantId, String assetId) {
        ensureOpen();
        get(tenantId, assetId);
        try {
            InputStream input = backend.open(tenantPrefix(tenantId) + assetId);
            if (input == null) backendFailure(false);
            return new BufferedInputStream(input);
        } catch (ObjectMediaBackendException e) {
            backendFailure(e);
            return null;
        } catch (ClaraException e) {
            throw e;
        } catch (RuntimeException e) {
            backendFailure(false);
            return null;
        }
    }

    public synchronized void delete(String tenantId, String assetId) {
        ensureOpen();
        validateIdentity(tenantId, assetId);
        String key = tenantPrefix(tenantId) + assetId;
        try {
            ObjectMediaRecord current = backend.head(key);
            if (current == null) notFound();
            verify(current, tenantId, assetId, key);
            backend.delete(key);
        } catch (ObjectMediaBackendException e) {
            backendFailure(e);
        } catch (ClaraException e) {
            throw e;
        } catch (RuntimeException e) {
            backendFailure(false);
        }
    }

    public synchronized int cleanupExpired(long now) {
        ensureOpen();
        try {
            List<ObjectMediaRecord> records = backend.list(config.getNamespace() + "/assets/");
            if (records == null) backendFailure(false);
            int removed = 0;
            for (ObjectMediaRecord record : records) {
                if (record == null || record.getAsset() == null) backendFailure(false);
                if (!record.getKey().startsWith(config.getNamespace() + "/assets/")) backendFailure(false);
                String key = record.getKey();
                int separator = key.lastIndexOf('/');
                if (separator < 0) backendFailure(false);
                verify(record, record.getAsset().getOwnerTenantId(), key.substring(separator + 1), key);
                Long expiry = record.getAsset().getExpiresAt();
                if (expiry != null && expiry.longValue() <= now) {
                    backend.delete(record.getKey());
                    removed++;
                }
            }
            return removed;
        } catch (ObjectMediaBackendException e) {
            backendFailure(e);
            return 0;
        } catch (ClaraException e) {
            throw e;
        } catch (RuntimeException e) {
            backendFailure(false);
            return 0;
        }
    }

    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        backend.close();
    }

    private void verify(ObjectMediaRecord record, String tenantId, String assetId, String key) {
        if (record == null || !key.equals(record.getKey()) || !assetId.equals(record.getSha256())
                || !key.equals(tenantPrefix(tenantId) + assetId)
                || record.getSize() != record.getAsset().getSize()
                || !assetId.equals(record.getAsset().getAssetId())
                || !assetId.equals(record.getAsset().getSha256())
                || !("clara-object:" + key).equals(record.getAsset().getStorageUri())
                || !tenantId.equals(record.getAsset().getOwnerTenantId())) backendFailure(false);
    }

    private String tenantPrefix(String tenantId) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        return config.getNamespace() + "/assets/" + sha256(tenantId.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "/";
    }

    private static void validateIdentity(String tenantId, String assetId) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (assetId == null || !assetId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid assetId");
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("media store is closed");
        }
    }

    private void deleteQuietly(String key) {
        try {
            backend.delete(key);
        } catch (RuntimeException ignored) {
        }
    }

    private static DigestAndSize digest(InputStream source, long limit) throws IOException {
        if (source == null) {
            throw new IOException("backend returned null content");
        }
        MessageDigest digest = sha256Digest();
        long size = 0;
        InputStream input = new BufferedInputStream(source);
        try {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count == 0) continue;
                if (size > limit - count) resourceLimit();
                digest.update(buffer, 0, count);
                size += count;
            }
        } finally {
            input.close();
        }
        return new DigestAndSize(hex(digest.digest()), size);
    }

    private static String sha256(byte[] value) {
        return hex(sha256Digest().digest(value));
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

    private static void notFound() {
        fail(ErrorCodes.MEDIA_NOT_FOUND, ErrorCategory.VALIDATION, false);
    }

    private static void invalid() {
        fail(ErrorCodes.MEDIA_VALIDATION_FAILED, ErrorCategory.VALIDATION, false);
    }

    private static void resourceLimit() {
        fail(ErrorCodes.MEDIA_RESOURCE_LIMIT, ErrorCategory.RESOURCE_LIMIT, false);
    }

    private static void backendFailure(ObjectMediaBackendException error) {
        if (error.isResourceLimit()) resourceLimit();
        backendFailure(error.isRetryable());
    }

    private static void backendFailure(boolean retryable) {
        fail(ErrorCodes.MEDIA_BACKEND_FAILED, ErrorCategory.EXTERNAL_SERVICE, retryable);
    }

    private static void fail(String code, ErrorCategory category, boolean retryable) {
        throw new ClaraException(new ClaraError(code, category, code, retryable, null, null, null, null, Collections.<String, String>emptyMap()));
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
}
