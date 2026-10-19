package com.clara.dto.planner;

import com.clara.utils.StringUtils;
import lombok.Data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务执行上下文：向任务处理器提供计划、尝试次数、幂等键与可读值。
 */
@Data
public final class TaskExecutionContext {

    private final TaskPlan plan;
    private final int attempt;
    private final String idempotencyKey;
    private final Map<String, Object> values;

    /**
     * 创建任务执行上下文。
     *
     * @param plan          所属任务计划，不可为 null
     * @param attempt       本次尝试的序号，从 1 起，不可小于 1
     * @param idempotencyKey 本次执行的幂等键，不可为空白
     * @param values        当前可读的变量映射，不可为 null
     * @throws IllegalArgumentException 任意字段不合法时
     */
    public TaskExecutionContext(TaskPlan plan, int attempt, String idempotencyKey, Map<String, Object> values) {
        if (plan == null || attempt < 1 || StringUtils.isBlank(idempotencyKey) || values == null) {
            throw new IllegalArgumentException("task execution context is invalid");
        }
        this.plan = plan;
        this.attempt = attempt;
        this.idempotencyKey = idempotencyKey;
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

}
