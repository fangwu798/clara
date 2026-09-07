package com.clara.media;

import java.io.IOException;

/**
 * 扫描并评估媒体内容安全。
 */
public interface MediaContentSafetyScanner {
    MediaSafetyAssessment scan(MediaUpload upload, MediaValidation validation, MediaInput content)
            throws IOException;
}
