package com.clara.planner;

/**
 * 表示任务副作用的执行结果无法确定时发生的异常。
 */
public final class UnknownSideEffectException extends RuntimeException {
    public UnknownSideEffectException(String message) { super(message); }
}
