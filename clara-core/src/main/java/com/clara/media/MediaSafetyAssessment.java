package com.clara.media;

import lombok.Data;

/**
 * 表示。
 */
@Data
public final class MediaSafetyAssessment {
    private final boolean allowed;
    private final String reasonCode;

    private MediaSafetyAssessment(boolean allowed, String reasonCode) {
        this.allowed = allowed;
        this.reasonCode = reasonCode;
    }

    public static MediaSafetyAssessment allowed() {
        return new MediaSafetyAssessment(true, null);
    }
}
