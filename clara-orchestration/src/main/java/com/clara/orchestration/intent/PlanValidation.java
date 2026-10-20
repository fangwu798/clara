package com.clara.orchestration.intent;

import com.clara.dto.planner.ExecutionPlan;

/**
 * 执行计划校验结果：成功时带值对象，失败时带可反馈给模型的错误。
 */
final class PlanValidation {

    /** 是否通过校验。 */
    final boolean ok;

    /** 通过校验的计划；失败时为 null。 */
    final ExecutionPlan plan;

    /** 失败原因；成功时为 null。 */
    final String error;

    private PlanValidation(boolean ok, ExecutionPlan plan, String error) {
        this.ok = ok;
        this.plan = plan;
        this.error = error;
    }

    static PlanValidation passed(ExecutionPlan plan) {
        return new PlanValidation(true, plan, null);
    }

    static PlanValidation fail(String error) {
        return new PlanValidation(false, null, error);
    }
}
