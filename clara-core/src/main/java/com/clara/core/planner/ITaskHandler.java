package com.clara.core.planner;

import com.clara.dto.planner.TaskExecutionContext;
import com.clara.dto.planner.TaskExecutionResult;

/**
 * 任务处理器接口：执行单个任务节点，返回执行结果。
 */
public interface ITaskHandler {

    /**
     * 执行任务。
     *
     * @param context 任务执行上下文，不可为 null
     * @return 执行结果，非 null
     * @throws Exception 执行失败时
     */
    TaskExecutionResult execute(TaskExecutionContext context) throws Exception;
}
