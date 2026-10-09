package com.clara.memory.semantic.milvus;

/**
 * Milvus 后端异常：把 Milvus SDK / 服务的失败封装为领域异常，隔离 SDK 交互细节。
 */
public class MilvusBackendException extends RuntimeException {

    private final boolean retryable;

    /**
     * 创建 Milvus 后端异常。
     *
     * @param message   错误描述
     * @param retryable 是否可重试
     */
    public MilvusBackendException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    /**
     * 创建带原因的 Milvus 后端异常。
     *
     * @param message   错误描述
     * @param retryable 是否可重试
     * @param cause     底层原因
     */
    public MilvusBackendException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
