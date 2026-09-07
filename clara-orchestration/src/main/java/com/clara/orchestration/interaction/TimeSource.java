package com.clara.orchestration.interaction;

/**
 * 为交互工作流提供当前时间。
 */
public interface TimeSource {
    long currentTimeMillis();
}
