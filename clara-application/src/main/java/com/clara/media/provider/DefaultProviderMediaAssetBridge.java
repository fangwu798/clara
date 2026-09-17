package com.clara.media.provider;

import com.clara.kernel.ClaraError;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCategory;
import com.clara.kernel.ErrorCodes;
import com.clara.kernel.InvocationContext;
import com.clara.media.MediaAsset;
import com.clara.media.MediaInput;
import com.clara.media.MediaStore;
import com.clara.media.MediaType;
import com.clara.media.MediaUpload;
import com.clara.model.provider.media.ProviderMediaAssetBridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;

/**
 * 在 Clara 媒体资产与服务提供商所需的 HTTPS 引用、字节输入及远程输出之间转换。
 *
 * <p>常规用法：模型发送输入时按服务提供商能力选择 {@link #inputReference} 或 {@link #inputBytes}；
 * 收到远程输出时调用 {@link #storeRemote}，收到内联字节时调用 {@link #storeBytes}。
 *
 * <p>注意：所有读取均按调用上下文的租户校验资产归属和媒体类型。远程 URL 只接受无用户信息、无 fragment
 * 的 HTTPS 地址；字节读取必须设置上限，未配置发布器或远程摄取器时对应能力不可用。
 */
public final class DefaultProviderMediaAssetBridge implements ProviderMediaAssetBridge {
    private final MediaStore store;
    private final MediaAssetReferencePublisher publisher;
    private final RemoteMediaIngestor ingestor;

    public DefaultProviderMediaAssetBridge(MediaStore store,
                                           MediaAssetReferencePublisher publisher, RemoteMediaIngestor ingestor) {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        this.store = store;
        this.publisher = publisher;
        this.ingestor = ingestor;
    }

    public String inputReference(String assetId, MediaType expectedType, InvocationContext context) {
        MediaAsset asset = checked(assetId, expectedType, context);
        if (publisher == null) fail(ErrorCodes.MEDIA_TRANSPORT_NOT_SUPPORTED, ErrorCategory.CONFIGURATION);
        String reference = publisher.publish(asset, context);
        if (!safeHttps(reference)) fail(ErrorCodes.MEDIA_TRANSPORT_NOT_SUPPORTED, ErrorCategory.CONFIGURATION);
        return reference;
    }

    public byte[] inputBytes(String assetId, MediaType expectedType, int maxBytes,
                             InvocationContext context) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        checked(assetId, expectedType, context);
        InputStream input = store.open(context.getTenantId(), assetId);
        if (input == null) fail(ErrorCodes.MEDIA_NOT_FOUND, ErrorCategory.VALIDATION);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                if (total > maxBytes - read) fail(ErrorCodes.MEDIA_RESOURCE_LIMIT, ErrorCategory.RESOURCE_LIMIT);
                output.write(buffer, 0, read);
                total += read;
            }
            return output.toByteArray();
        } catch (IOException failure) {
            fail(ErrorCodes.MEDIA_STORE_IO_ERROR, ErrorCategory.INTERNAL);
            return null;
        } finally {
            try {
                input.close();
            } catch (IOException ignored) {
            }
        }
    }

    public String storeRemote(String sourceUrl, MediaType type, String mimeType,
                              InvocationContext context) {
        return store.put(remoteUpload(sourceUrl, type, mimeType, context)).getAssetId();
    }

    public MediaUpload remoteUpload(String sourceUrl, MediaType type, String mimeType,
                                    InvocationContext context) {
        if (ingestor == null || !safeHttps(sourceUrl))
            fail(ErrorCodes.MEDIA_TRANSPORT_NOT_SUPPORTED, ErrorCategory.CONFIGURATION);
        MediaUpload upload = ingestor.fetch(sourceUrl, type, mimeType, context);
        if (upload == null || !context.getTenantId().equals(upload.getTenantId())
                || upload.getMediaType() != type)
            fail(ErrorCodes.MEDIA_VALIDATION_FAILED, ErrorCategory.VALIDATION);
        return upload;
    }

    public String storeBytes(byte[] content, final MediaType type, final String mimeType,
                             final InvocationContext context) {
        if (content == null || content.length == 0)
            fail(ErrorCodes.MEDIA_VALIDATION_FAILED, ErrorCategory.VALIDATION);
        final byte[] copy = content.clone();
        MediaUpload upload = new MediaUpload(context.getTenantId(), type, fileName(type), mimeType,
                null, null, null, null, null, Collections.<String, String>emptyMap(), new MediaInput() {
            public InputStream open() {
                return new ByteArrayInputStream(copy);
            }
        });
        return store.put(upload).getAssetId();
    }

    private MediaAsset checked(String id, MediaType type, InvocationContext context) {
        if (context == null || type == null) {
            throw new IllegalArgumentException("context and type must not be null");
        }
        context.getCancellationToken().throwIfCancellationRequested(context.getTraceId());
        MediaAsset asset = store.get(context.getTenantId(), id);
        if (asset == null) fail(ErrorCodes.MEDIA_NOT_FOUND, ErrorCategory.VALIDATION);
        if (asset.getMediaType() != type) fail(ErrorCodes.MEDIA_VALIDATION_FAILED, ErrorCategory.VALIDATION);
        return asset;
    }

    private static boolean safeHttps(String value) {
        try {
            java.net.URI uri = new java.net.URI(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getFragment() == null;
        } catch (Exception failure) {
            return false;
        }
    }

    private static String fileName(MediaType type) {
        return "provider-output." + type.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static void fail(String code, ErrorCategory category) {
        throw new ClaraException(new ClaraError(code, category, code, false, null, null, null,
                null, Collections.<String, String>emptyMap()));
    }
}
