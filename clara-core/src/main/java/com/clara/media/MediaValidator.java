package com.clara.media;

import java.io.IOException;

public interface MediaValidator {
    MediaValidation validate(MediaUpload upload, MediaInput content, long size, long tenantUsageBytes)
            throws IOException;
}
