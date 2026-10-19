package com.clara.dto.planner;

import com.clara.core.planner.ITaskHandler;
import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 任务节点：描述计划中单个任务的标识、依赖、能力、输出键、重试 / 超时 / 预算与处理器。
 */
@Data
public final class TaskNode {

    private final String taskId;
    private final List<String> dependencies;
    private final String capability;
    private final String outputKey;
    private final RetryPolicy retryPolicy;
    private final long timeoutMillis;
    private final BudgetAmount budget;
    private final ITaskHandler handler;

    /**
     * 创建任务节点。
     *
     * @param taskId        任务标识，不可为空白
     * @param dependencies  依赖的任务标识列表，可为 null（无依赖）
     * @param capability    任务所需能力标识，不可为空白
     * @param outputKey     任务输出写入的变量键，不可为空白
     * @param retryPolicy   重试策略，不可为 null
     * @param timeoutMillis 单次任务超时（毫秒），必须为正
     * @param budget        任务预算上限，不可为 null
     * @param handler       任务处理器，不可为 null
     * @throws IllegalArgumentException 任一字段不合法时
     */
    public TaskNode(String taskId, List<String> dependencies, String capability, String outputKey,
            RetryPolicy retryPolicy, long timeoutMillis, BudgetAmount budget, ITaskHandler handler) {
        if (blank(taskId) || blank(capability) || blank(outputKey) || retryPolicy == null
                || timeoutMillis <= 0L || budget == null || handler == null) {
            throw new IllegalArgumentException("task node is invalid");
        }
        List<String> copied = new ArrayList<>();
        if (dependencies != null) {
            for (String dependency : dependencies) {
                if (blank(dependency)) {
                    throw new IllegalArgumentException("dependency must not be blank");
                }
                copied.add(dependency);
            }
        }
        this.taskId = taskId;
        this.dependencies = Collections.unmodifiableList(copied);
        this.capability = capability;
        this.outputKey = outputKey;
        this.retryPolicy = retryPolicy;
        this.timeoutMillis = timeoutMillis;
        this.budget = budget;
        this.handler = handler;
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
