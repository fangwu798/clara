package com.clara.media;

/**
 * 表示。
 */
public final class MediaSafetyAssessment {
    private final boolean allowed;
    private final String reasonCode;

    private MediaSafetyAssessment(boolean allowed, String reasonCode) {
        this.allowed = allowed;
        this.reasonCode = reasonCode;
    }
    public static MediaSafetyAssessment allowed() { return new MediaSafetyAssessment(true, null); }
    public static MediaSafetyAssessment rejected(String reasonCode) {
        if (reasonCode == null || reasonCode.trim().isEmpty()) {
            throw new IllegalArgumentException("reasonCode must not be blank");
        }
        return new MediaSafetyAssessment(false, reasonCode);
    }
    public boolean isAllowed() { return allowed; }
    public String getReasonCode() { return reasonCode; }
}
