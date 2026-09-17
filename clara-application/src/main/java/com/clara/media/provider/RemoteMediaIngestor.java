package com.clara.media.provider;

import com.clara.kernel.InvocationContext;
import com.clara.media.MediaType;
import com.clara.media.MediaUpload;

/**
 * 将远程媒体摄取到本地存储。
 */
public interface RemoteMediaIngestor {
    MediaUpload fetch(String sourceUrl, MediaType expectedType, String expectedMimeType,
                      InvocationContext context);
}
