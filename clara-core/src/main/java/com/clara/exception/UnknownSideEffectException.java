package com.clara.exception;

/**
 * 任务副作用执行结果无法确定时抛出的异常。
 */
public final class UnknownSideEffectException extends RuntimeException {

    /**
     * 创建未知副作用异常。
     *
     * @param message 错误描述
     */
    public UnknownSideEffectException(String message) {
        super(message);
    }
}
