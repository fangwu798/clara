package com.clara.memory.semantic.milvus;

public final class MilvusBackendException extends RuntimeException {
    private final boolean retryable;

    public MilvusBackendException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
