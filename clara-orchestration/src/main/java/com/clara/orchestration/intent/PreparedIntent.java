package com.clara.orchestration.intent;

import com.clara.dto.planner.ExecutionPlan;
import com.clara.utils.StringUtils;
import lombok.Data;

/**
 * 一次用户输入的意图判断结果：简单任务直接交给模型，复杂任务携带通过校验的执行计划。
 */
@Data
public final class PreparedIntent {

    /** 通过校验的执行计划；简单任务时为 null。 */
    private final ExecutionPlan plan;

    /** 判断依据，用于控制台观测与排查。 */
    private final String reason;

    private PreparedIntent(ExecutionPlan plan, String reason) {
        this.plan = plan;
        this.reason = reason;
    }

    /**
     * 构造简单任务结果。
     *
     * @param reason 判断依据，可为空
     * @return 简单任务结果，非 null
     */
    public static PreparedIntent simple(String reason) {
        return new PreparedIntent(null, StringUtils.isBlank(reason) ? "简单任务" : reason.trim());
    }

    /**
     * 构造复杂任务结果。
     *
     * @param plan   通过校验的执行计划，不可为 null
     * @param reason 判断依据，可为空
     * @return 复杂任务结果，非 null
     */
    public static PreparedIntent complex(ExecutionPlan plan, String reason) {
        if (plan == null) {
            throw new IllegalArgumentException("plan must not be null");
        }
        return new PreparedIntent(plan, StringUtils.isBlank(reason) ? "复杂任务" : reason.trim());
    }

    /**
     * 是否为复杂任务（携带执行计划）。
     *
     * @return true 表示本轮按计划执行
     */
    public boolean isComplex() {
        return plan != null;
    }

    /**
     * 取注入 system 指令的计划文本。
     *
     * @return 计划文本；简单任务时为 null
     */
    public String prompt() {
        return plan == null ? null : plan.toPrompt();
    }
}