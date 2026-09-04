package com.clara.planner;

public interface TaskHandler {
    TaskExecutionResult execute(TaskExecutionContext context) throws Exception;
}
