package com.clara.planner;

/**
 * 处理任务操作。
 */
public interface TaskHandler {
    TaskExecutionResult execute(TaskExecutionContext context) throws Exception;
}
