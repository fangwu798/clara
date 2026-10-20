package com.clara.planner;

import com.clara.api.IBuiltinTool;
import com.clara.dto.planner.ExecutionPlan;
import com.clara.orchestration.intent.ExecutionPlanValidator;
import com.clara.utils.StringUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内置工具 {@code validate_execution_plan}：校验模型生成的执行计划格式。
 *
 * <p>入参 JSON：{@code {"plan": {...}}}，也接受直接传入计划对象。校验通过返回归一化后的计划；
 * 不通过返回 {@code {"ok":false,"error":"..."} }，错误文本可直接反馈给模型修正。
 */
public final class ValidateExecutionPlanTool implements IBuiltinTool {

    /** 工具名，需与 {@code config/skill/tools/builtin/validate_execution_plan.json} 一致。 */
    public static final String TOOL_NAME = "validate_execution_plan";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ExecutionPlanValidator validator;

    /**
     * 创建计划校验工具。
     *
     * @param validator 计划校验器，不可为 null
     * @throws IllegalArgumentException validator 为 null 时
     */
    public ValidateExecutionPlanTool(ExecutionPlanValidator validator) {
        if (validator == null) {
            throw new IllegalArgumentException("validator must not be null");
        }
        this.validator = validator;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String execute(String argumentsJson) {
        try {
            JsonNode root = parse(argumentsJson);
            JsonNode planNode = root != null && root.has("plan") ? root.get("plan") : root;
            ExecutionPlan plan = validator.validate(planNode);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ok", Boolean.TRUE);
            payload.put("goal", plan.getGoal());
            payload.put("stepCount", plan.getSteps().size());
            payload.put("plan", plan);
            return JSON.writeValueAsString(payload);
        } catch (RuntimeException | JsonProcessingException failure) {
            return error(message(failure));
        }
    }

    private static JsonNode parse(String argumentsJson) {
        if (StringUtils.isBlank(argumentsJson)) {
            throw new IllegalArgumentException("缺少 plan 参数");
        }
        try {
            return JSON.readTree(argumentsJson);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("入参不是合法 JSON：" + failure.getOriginalMessage(), failure);
        }
    }

    private static String error(String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", Boolean.FALSE);
        payload.put("error", message);
        try {
            return JSON.writeValueAsString(payload);
        } catch (JsonProcessingException ignored) {
            return "{\"ok\":false,\"error\":\"计划校验失败\"}";
        }
    }

    private static String message(Throwable failure) {
        String message = failure.getMessage();
        return StringUtils.isBlank(message) ? failure.getClass().getSimpleName() : message;
    }
}
