//package com.clara.media.provider;
//
//import com.clara.context.CallContext;
//import com.clara.kernel.exception.ClaraError;
//import com.clara.kernel.exception.ClaraException;
//import com.clara.context.ConvContext;
//import com.clara.kernel.exception.ErrorCategory;
//import com.clara.kernel.exception.ErrorCodes;
//import com.clara.media.MediaAsset;
//import com.clara.media.MediaInput;
//import com.clara.media.MediaStore;
//import com.clara.media.MediaType;
//import com.clara.media.MediaUpload;
//import com.clara.model.provider.media.IProviderMediaAssetBridge;
//
//import java.io.ByteArrayInputStream;
//import java.io.ByteArrayOutputStream;
//import java.io.IOException;
//import java.io.InputStream;
//import java.util.Collections;
//
///**
// * 在 Clara 媒体资产与服务提供商所需的 HTTPS 引用、字节输入及远程输出之间转换。
// *
// * <p>常规用法：模型发送输入时按服务提供商能力选择 {@link #inputReference} 或 {@link #inputBytes}；
// * 收到远程输出时调用 {@link #storeRemote}，收到内联字节时调用 {@link #storeBytes}。
// *
// * <p>注意：所有读取均校验资产归属与媒体类型。远程 URL 只接受无用户信息、无 fragment
// * 的 HTTPS 地址；字节读取必须设置上限，未配置发布器或远程摄取器时对应能力不可用。
// *
// * <p>租户语义：会话上下文（{@link ConvContext}）已不再携带租户标识，而 {@link MediaStore} 的读写仍按租户
// * 分区，本实现因此统一使用单租户命名空间 {@value #DEFAULT_TENANT}。多租户宿主必须自行提供带租户维度的
// * 桥实现，不要复用本类。
// */
//public final class DefaultProviderMediaAssetBridge implements IProviderMediaAssetBridge {
//    /**
//     * 媒体存储使用的租户命名空间。会话上下文不再提供租户，本实现退化为单租户；{@link MediaUpload} 写回
//     * 时也使用该值。
//     */
//    private static final String DEFAULT_TENANT = "local";
//    private final MediaStore store;
//    private final MediaAssetReferencePublisher publisher;
//    private final RemoteMediaIngestor ingestor;
//
//    public DefaultProviderMediaAssetBridge(MediaStore store,
//                                           MediaAssetReferencePublisher publisher, RemoteMediaIngestor ingestor) {
//        if (store == null) {
//            throw new IllegalArgumentException("store must not be null");
//        }
//        this.store = store;
//        this.publisher = publisher;
//        this.ingestor = ingestor;
//    }
//
//    public String inputReference(String assetId, MediaType expectedType, ConvContext conversation,
//                                 CallContext call) {
//        MediaAsset asset = checked(assetId, expectedType, conversation, call);
//        if (publisher == null) fail(ErrorCodes.MEDIA_TRANSPORT_NOT_SUPPORTED, ErrorCategory.CONFIGURATION);
//        String reference = publisher.publish(asset, call);
//        if (!safeHttps(reference)) fail(ErrorCodes.MEDIA_TRANSPORT_NOT_SUPPORTED, ErrorCategory.CONFIGURATION);
//        return reference;
//    }
//
//    public byte[] inputBytes(String assetId, MediaType expectedType, int maxBytes,
//                             ConvContext conversation, CallContext call) {
//        if (maxBytes <= 0) {
//            throw new IllegalArgumentException("maxBytes must be positive");
//        }
//        checked(assetId, expectedType, conversation, call);
//        InputStream input = store.open(DEFAULT_TENANT, assetId);
//        if (input == null) fail(ErrorCodes.MEDIA_NOT_FOUND, ErrorCategory.VALIDATION);
//        try {
//            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
//            byte[] buffer = new byte[8192];
//            int total = 0;
//            int read;
//            while ((read = input.read(buffer)) >= 0) {
//                if (read == 0) continue;
//                if (total > maxBytes - read) fail(ErrorCodes.MEDIA_RESOURCE_LIMIT, ErrorCategory.RESOURCE_LIMIT);
//                output.write(buffer, 0, read);
//                total += read;
//            }
//            return output.toByteArray();
//        } catch (IOException failure) {
//            fail(ErrorCodes.MEDIA_STORE_IO_ERROR, ErrorCategory.INTERNAL);
//            return null;
//        } finally {
//            try {
//                input.close();
//            } catch (IOException ignored) {
//            }
//        }
//    }
//
//    public String storeRemote(String sourceUrl, MediaType type, String mimeType,
//                              ConvContext conversation, CallContext call) {
//        return store.put(remoteUpload(sourceUrl, type, mimeType, conversation, call)).getAssetId();
//    }
//
//    public MediaUpload remoteUpload(String sourceUrl, MediaType type, String mimeType,
//                                    ConvContext conversation, CallContext call) {
//        if (ingestor == null || !safeHttps(sourceUrl))
//            fail(ErrorCodes.MEDIA_TRANSPORT_NOT_SUPPORTED, ErrorCategory.CONFIGURATION);
//        MediaUpload upload = ingestor.fetch(sourceUrl, type, mimeType, conversation, call);
//        if (upload == null || upload.getMediaType() != type)
//            fail(ErrorCodes.MEDIA_VALIDATION_FAILED, ErrorCategory.VALIDATION);
//        return upload;
//    }
//
//    public String storeBytes(byte[] content, final MediaType type, final String mimeType,
//                             final ConvContext conversation, final CallContext call) {
//        if (content == null || content.length == 0)
//            fail(ErrorCodes.MEDIA_VALIDATION_FAILED, ErrorCategory.VALIDATION);
//        final byte[] copy = content.clone();
//        MediaUpload upload = new MediaUpload(DEFAULT_TENANT, type, fileName(type), mimeType,
//                null, null, null, null, null, Collections.<String, String>emptyMap(), new MediaInput() {
//            public InputStream open() {
//                return new ByteArrayInputStream(copy);
//            }
//        });
//        return store.put(upload).getAssetId();
//    }
//
//    private MediaAsset checked(String id, MediaType type, ConvContext conversation, CallContext call) {
//        if (conversation == null || call == null || type == null) {
//            throw new IllegalArgumentException("conversation, call and type must not be null");
//        }
//        call.getCancellationToken().throwIfCancellationRequested(call.getTraceId());
//        MediaAsset asset = store.get(DEFAULT_TENANT, id);
//        if (asset == null) fail(ErrorCodes.MEDIA_NOT_FOUND, ErrorCategory.VALIDATION);
//        if (asset.getMediaType() != type) fail(ErrorCodes.MEDIA_VALIDATION_FAILED, ErrorCategory.VALIDATION);
//        return asset;
//    }
//
//    private static boolean safeHttps(String value) {
//        try {
//            java.net.URI uri = new java.net.URI(value);
//            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
//                    && uri.getUserInfo() == null && uri.getFragment() == null;
//        } catch (Exception failure) {
//            return false;
//        }
//    }
//
//    private static String fileName(MediaType type) {
//        return "provider-output." + type.name().toLowerCase(java.util.Locale.ROOT);
//    }
//
//    private static void fail(String code, ErrorCategory category) {
//        throw new ClaraException(new ClaraError(code, category, code, false, null, null, null,
//                null, Collections.<String, String>emptyMap()));
//    }
//}
