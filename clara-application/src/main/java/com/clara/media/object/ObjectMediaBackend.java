package com.clara.media.object;

import com.clara.media.MediaAsset;
import java.io.Closeable;
import java.io.InputStream;
import java.util.List;

/**
 * 定义。
 */
public interface ObjectMediaBackend extends Closeable {
    // stage must enforce maxBytes while streaming and return a checksum for the stored bytes.
    StagedMediaObject stage(String stagingKey, InputStream content, long maxBytes);
    InputStream open(String key);
    ObjectMediaRecord head(String key);
    List<ObjectMediaRecord> list(String prefix);
    long usage(String prefix);
    // Implementations must atomically enforce quota and never overwrite an existing destination.
    ObjectMediaRecord publishIfAbsent(String stagingKey, String destinationKey, String tenantPrefix,
            long maxTenantBytes, MediaAsset asset);
    void delete(String key);
}
