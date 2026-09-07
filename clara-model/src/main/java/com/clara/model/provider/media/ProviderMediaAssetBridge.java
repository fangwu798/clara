package com.clara.model.provider.media;

import com.clara.media.MediaType;
import com.clara.media.MediaUpload;
import com.clara.kernel.InvocationContext;

/**
 * 隔离服务提供商适配器与媒体存储、签名 URL 的具体实现。
 */
public interface ProviderMediaAssetBridge {
    String inputReference(String assetId, MediaType expectedType, InvocationContext context);

    byte[] inputBytes(String assetId, MediaType expectedType, int maxBytes, InvocationContext context);

    String storeRemote(String sourceUrl, MediaType type, String mimeType, InvocationContext context);

    MediaUpload remoteUpload(String sourceUrl, MediaType type, String mimeType, InvocationContext context);

    String storeBytes(byte[] content, MediaType type, String mimeType, InvocationContext context);
}
