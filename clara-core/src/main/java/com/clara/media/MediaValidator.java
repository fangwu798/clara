package com.clara.media;

import java.io.IOException;

/**
 * 校验媒体是否符合约束。
 */
public interface MediaValidator {
    MediaValidation validate(MediaUpload upload, MediaInput content, long size, long tenantUsageBytes)
            throws IOException;
}
