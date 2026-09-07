package com.clara.planner;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 向任务处理器提供当前计划、任务尝试次数、幂等标识及可读取的执行值。
 *
 * <p>该上下文由 {@link TaskPlanEngine} 为每次任务尝试创建，并传给 {@link TaskHandler#execute}；
 * 业务处理器可通过 {@link #get} 按键读取计划输入和已完成依赖任务的输出。
 *
 * <p>注意：上下文仅描述单次尝试，不应跨任务或跨运行缓存。值映射本身不可修改且创建时会复制，
 * 但其中的对象不会深拷贝；处理器不得修改共享的可变值。外部副作用应使用幂等键去重。
 */
public final class TaskExecutionContext {
    /**
     * 当前任务所属计划的稳定标识，用于关联同一计划的执行记录。
     */
    private final String planId;
    /**
     * 当前计划定义的版本号，用于区分同一计划标识下不同版本的执行语义。
     */
    private final int planVersion;
    /**
     * 当前正在执行的任务节点标识，在计划内唯一。
     */
    private final String taskId;
    /**
     * 当前任务的尝试序号；首次执行为 1，后续重试依次递增。
     */
    private final int attempt;
    /**
     * 本次任务副作用的完整幂等键，由运行标识和任务幂等键组合生成，外部写操作应使用该值去重。
     */
    private final String idempotencyKey;
    /**
     * 当前任务可见的输入及已完成依赖输出快照；映射不可修改，值对象不会深拷贝。
     */
    private final Map<String, Object> values;

    TaskExecutionContext(String planId, int planVersion, String taskId, int attempt,
            String idempotencyKey, Map<String, Object> values) {
        this.planId = planId; this.planVersion = planVersion; this.taskId = taskId;
        this.attempt = attempt; this.idempotencyKey = idempotencyKey;
        this.values = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(values));
    }
    public String getPlanId() { return planId; }
    public int getPlanVersion() { return planVersion; }
    public String getTaskId() { return taskId; }
    public int getAttempt() { return attempt; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Map<String, Object> getValues() { return values; }
    public Object get(String key) { return values.get(key); }
}
