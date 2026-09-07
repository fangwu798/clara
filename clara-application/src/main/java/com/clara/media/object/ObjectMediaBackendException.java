package com.clara.media.object;

/**
 * 表示处理对象媒体后端时发生的异常。
 */
public final class ObjectMediaBackendException extends RuntimeException {
    private final boolean retryable;
    private final boolean resourceLimit;

    public ObjectMediaBackendException(String message, boolean retryable, Throwable cause) {
        this(message, retryable, false, cause);
    }
    public ObjectMediaBackendException(String message, boolean retryable, boolean resourceLimit, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.resourceLimit = resourceLimit;
    }
    public boolean isRetryable() { return retryable; }
    public boolean isResourceLimit() { return resourceLimit; }
}
