package com.clara.workspace.runtime;

import com.clara.kernel.ClaraError;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCategory;
import com.clara.kernel.ErrorCodes;
import com.clara.planner.TaskNode;
import com.clara.planner.TaskPlan;
import com.clara.planner.TaskPlanEngine;
import com.clara.planner.TaskPlanResult;
import com.clara.skill.api.SkillDefinition;
import com.clara.skill.api.SkillExecutionRequest;
import com.clara.skill.api.SkillExecutionResult;
import com.clara.skill.api.SkillExecutionStatus;
import com.clara.skill.api.SkillResumeRequest;
import com.clara.skill.runtime.SkillHandler;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将通用工作区技能请求转换为受约束的任务计划并交由规划引擎执行。
 *
 * <p>常规用法：通过 {@link com.clara.skill.runtime.SkillHandler} 路由调用 {@link #execute}；
 * 有人工反馈时使用 {@link #resume}，反馈会作为只读输入加入新计划。
 *
 * <p>注意：请求必须包含非空的 {@code goal} 和 run ID。计划中的每项 capability 都必须出现在技能定义的
 * required tools 中；run ID 与实例 ID 共同形成规划执行标识，调用方应保持稳定以维持幂等性。
 */
public final class GeneralWorkspaceSkillHandler implements SkillHandler {
    private final TaskPlanEngine engine;
    private final GeneralWorkspacePlanFactory plans;

    public GeneralWorkspaceSkillHandler(TaskPlanEngine engine, GeneralWorkspacePlanFactory plans) {
        if (engine == null || plans == null) { throw new IllegalArgumentException("handler dependencies are required"); }
        this.engine = engine;
        this.plans = plans;
    }

    public SkillExecutionResult execute(SkillDefinition definition, SkillExecutionRequest request) {
        return executePlan(definition, request, Collections.<String, Object>emptyMap(),
                instanceId(request), 1L);
    }

    public SkillExecutionResult resume(SkillDefinition definition, SkillResumeRequest request) {
        return executePlan(definition, request.getExecutionRequest(), request.getFeedback(),
                request.getInstanceId(), request.getExpectedVersion() + 1L);
    }

    private SkillExecutionResult executePlan(SkillDefinition definition, SkillExecutionRequest request,
            Map<String, Object> feedback, String instanceId, long instanceVersion) {
        Object goal = request.getInputs().get("goal");
        if (!(goal instanceof String) || ((String) goal).trim().isEmpty()) invalid(ErrorCodes.SKILL_SCHEMA_INVALID, ErrorCategory.VALIDATION);
        String runId = request.getContext().getRunId();
        if (runId == null || runId.trim().isEmpty()) invalid(ErrorCodes.INVALID_ARGUMENT, ErrorCategory.VALIDATION);
        request.getContext().getCancellationToken().throwIfCancellationRequested(request.getContext().getTraceId());
        TaskPlan plan = plans.create(definition, request, feedback);
        if (plan == null) invalid(ErrorCodes.PLAN_INVALID, ErrorCategory.VALIDATION);
        for (TaskNode task : plan.getTasks()) {
            String capability = task.getCapability();
            if (capability == null || !definition.getRequiredTools().contains(capability)) {
                invalid(ErrorCodes.PLAN_CAPABILITY_DENIED, ErrorCategory.AUTHORIZATION);
            }
        }
        Map<String, Object> inputs = new LinkedHashMap<String, Object>(request.getInputs());
        if (!feedback.isEmpty()) inputs.put("feedback", Collections.unmodifiableMap(new LinkedHashMap<String, Object>(feedback)));
        TaskPlanResult result = engine.execute(plan, runId + ":" + instanceId, inputs);
        Map<String, Object> outputs = new LinkedHashMap<String, Object>();
        outputs.put("goal", goal);
        outputs.put("planId", result.getPlan().getPlanId());
        outputs.put("planVersion", Integer.valueOf(result.getPlan().getPlanVersion()));
        outputs.put("executedSteps", Integer.valueOf(result.getSteps()));
        outputs.put("values", result.getOutputs());
        return new SkillExecutionResult(definition.getSkillId(), definition.getVersion(), instanceId,
                SkillExecutionStatus.SUCCEEDED, outputs, instanceVersion);
    }

    private static String instanceId(SkillExecutionRequest request) {
        String value = request.getContext().getInstanceId();
        return value == null || value.trim().isEmpty() ? request.getContext().getRunId() : value;
    }

    private static void invalid(String code, ErrorCategory category) {
        throw new ClaraException(new ClaraError(code, category, code, false, null, null, null, null,
                Collections.<String, String>emptyMap()));
    }
}
