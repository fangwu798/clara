package com.clara.media.provider;

import com.clara.kernel.InvocationContext;
import com.clara.media.MediaAsset;

/**
 * 发布媒体资产引用。
 */
public interface MediaAssetReferencePublisher {
    String publish(MediaAsset asset, InvocationContext context);
}
