package com.clara.media.job;

public final class MediaJobProviderException extends RuntimeException {
    private final boolean retryable;
    private final boolean sideEffectUnknown;

    public MediaJobProviderException(String message, boolean retryable,
            boolean sideEffectUnknown, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.sideEffectUnknown = sideEffectUnknown;
    }
    public boolean isRetryable() { return retryable; }
    public boolean isSideEffectUnknown() { return sideEffectUnknown; }
}
