package com.clara.media;

import java.io.IOException;

public interface MediaContentSafetyScanner {
    MediaSafetyAssessment scan(MediaUpload upload, MediaValidation validation, MediaInput content)
            throws IOException;
}
