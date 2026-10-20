package com.clara.workflow.planner;

import com.clara.dto.planner.TaskExecutionResult;

/**
 * 任务执行结果包装：承载调度线程返回的任务结果，供收集阶段读取。
 */
final class TaskOutcome {

    /** 任务执行结果。 */
    final TaskExecutionResult result;

    /**
     * 创建任务执行结果包装。
     *
     * @param result 任务执行结果
     */
    TaskOutcome(TaskExecutionResult result) {
        this.result = result;
    }
}