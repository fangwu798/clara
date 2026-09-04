package com.clara.media;

import java.io.InputStream;

public interface MediaStore {
    MediaAsset put(MediaUpload upload);
    MediaAsset get(String tenantId, String assetId);
    InputStream open(String tenantId, String assetId);
    void delete(String tenantId, String assetId);
}
