package com.clara.core.planner;

import com.clara.dto.planner.TaskPlan;
import com.clara.dto.planner.TaskPlanResult;

import java.io.Closeable;
import java.util.Map;

/**
 * 任务计划引擎接口：校验并执行任务 DAG，统一处理依赖调度、预算预留、重试、超时与执行幂等。
 *
 * <p>core 只定义契约，由 clara-workflow 提供实现；使用完毕后应调用 {@link #close()} 释放资源。
 */
public interface ITaskPlanEngine extends Closeable {

    /**
     * 执行任务计划。
     *
     * @param draft  待执行的计划草稿，不可为 null
     * @param runId  运行标识，不可为 null
     * @param inputs 初始输入变量，可为 null
     * @return 执行结果，非 null
     */
    TaskPlanResult execute(TaskPlan draft, String runId, Map<String, Object> inputs);
}
