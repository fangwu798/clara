package com.clara.dto.planner;

import com.clara.utils.StringUtils;
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

    /** 任务标识，不可为空白。 */
    private final String taskId;
    /** 依赖的任务标识列表，不可变副本。 */
    private final List<String> dependencies;
    /** 任务所需能力标识，不可为空白。 */
    private final String capability;
    /** 任务输出写入的变量键，不可为空白。 */
    private final String outputKey;
    /** 重试策略，不可为 null。 */
    private final RetryPolicy retryPolicy;
    /** 单次任务超时（毫秒），必须为正。 */
    private final long timeoutMillis;
    /** 任务预算上限，不可为 null。 */
    private final BudgetAmount budget;
    /** 任务处理器，不可为 null。 */
    private final ITaskHandler handler;

    /**
     * 由构建器创建任务节点并完成校验与防御性复制。
     *
     * @param builder 已填充字段的构建器，不能为 null
     * @throws IllegalArgumentException 任意字段不合法时
     */
    private TaskNode(Builder builder) {
        validate(builder);
        this.taskId = builder.taskId;
        this.dependencies = copyDependencies(builder.dependencies);
        this.capability = builder.capability;
        this.outputKey = builder.outputKey;
        this.retryPolicy = builder.retryPolicy;
        this.timeoutMillis = builder.timeoutMillis;
        this.budget = builder.budget;
        this.handler = builder.handler;
    }

    /**
     * 创建任务节点构建器。
     *
     * @param taskId     任务标识，不可为空白
     * @param capability 任务所需能力标识，不可为空白
     * @param outputKey  任务输出写入的变量键，不可为空白
     * @return 构建器，非 null
     */
    public static Builder builder(String taskId, String capability, String outputKey) {
        return new Builder(taskId, capability, outputKey);
    }

    /**
     * 校验构建器中的必填字段。
     *
     * @param builder 待校验构建器
     * @throws IllegalArgumentException 任意必填字段不合法时
     */
    private static void validate(Builder builder) {
        if (StringUtils.isBlank(builder.taskId) || StringUtils.isBlank(builder.capability)
                || StringUtils.isBlank(builder.outputKey) || builder.retryPolicy == null
                || builder.timeoutMillis <= 0L || builder.budget == null || builder.handler == null) {
            throw new IllegalArgumentException("task node is invalid");
        }
    }

    /**
     * 复制依赖列表为只读列表。
     *
     * @param dependencies 原始依赖列表，可为 null
     * @return 只读依赖列表，非 null
     * @throws IllegalArgumentException 依赖标识为空白时
     */
    private static List<String> copyDependencies(List<String> dependencies) {
        List<String> copied = new ArrayList<>();
        if (dependencies != null) {
            for (String dependency : dependencies) {
                if (StringUtils.isBlank(dependency)) {
                    throw new IllegalArgumentException("dependency must not be blank");
                }
                copied.add(dependency);
            }
        }
        return Collections.unmodifiableList(copied);
    }

    /**
     * 任务节点构建器；必填字段在工厂方法中传入，其余字段链式设置。
     */
    public static final class Builder {

        private final String taskId;
        private final String capability;
        private final String outputKey;
        private List<String> dependencies;
        private RetryPolicy retryPolicy;
        private long timeoutMillis;
        private BudgetAmount budget;
        private ITaskHandler handler;

        private Builder(String taskId, String capability, String outputKey) {
            this.taskId = taskId;
            this.capability = capability;
            this.outputKey = outputKey;
        }

        public Builder dependencies(List<String> dependencies) {
            this.dependencies = dependencies;
            return this;
        }

        public Builder retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = retryPolicy;
            return this;
        }

        public Builder timeoutMillis(long timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
            return this;
        }

        public Builder budget(BudgetAmount budget) {
            this.budget = budget;
            return this;
        }

        public Builder handler(ITaskHandler handler) {
            this.handler = handler;
            return this;
        }

        public TaskNode build() {
            return new TaskNode(this);
        }
    }
}
