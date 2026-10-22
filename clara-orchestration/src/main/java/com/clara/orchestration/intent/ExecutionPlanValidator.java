package com.clara.orchestration.intent;

import com.clara.dto.planner.ExecutionPlan;
import com.clara.dto.planner.ExecutionStep;
import com.clara.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 执行计划格式校验器：校验目标、步骤数、标识唯一性、依赖存在性与无环。
 *
 * <p>校验逻辑同时被编排层的规划流程与内置工具 {@code validate_execution_plan} 使用：
 * 模型生成计划后先过这一关，通过后才注入 system 指令并按步骤执行。
 */
@Data
public final class ExecutionPlanValidator {

    /**
     * 计划最大步骤数的默认值。
     */
    public static final int DEFAULT_MAX_STEPS = 12;

    private final int maxSteps;

    /**
     * 创建校验器。
     *
     * @param maxSteps 允许的最大步骤数，必须为正
     * @throws IllegalArgumentException maxSteps 非正时
     */
    public ExecutionPlanValidator(int maxSteps) {
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        this.maxSteps = maxSteps;
    }


    /**
     * 校验计划 JSON 并转换为值对象。
     *
     * <p>接受两种输入：计划对象本身（含 {@code goal} / {@code steps}），或外层包了一层
     * {@code {"plan": {...}}} 的响应。
     *
     * @param node 计划 JSON 节点，不可为 null
     * @return 校验通过的计划，非 null
     * @throws IllegalArgumentException 计划结构不合法时，消息可直接反馈给模型修正
     */
    public ExecutionPlan validate(JsonNode node) {
        JsonNode planNode = unwrap(node);
        if (planNode == null || !planNode.isObject()) {
            throw new IllegalArgumentException("计划必须是 JSON 对象");
        }
        String goal = text(planNode, "goal");
        if (goal == null) {
            throw new IllegalArgumentException("计划缺少非空的 goal");
        }
        JsonNode stepsNode = planNode.get("steps");
        if (stepsNode == null || !stepsNode.isArray() || stepsNode.isEmpty()) {
            throw new IllegalArgumentException("计划缺少 steps，且至少需要 1 个步骤");
        }
        if (stepsNode.size() > maxSteps) {
            throw new IllegalArgumentException("计划步骤数 " + stepsNode.size() + " 超过上限 " + maxSteps);
        }
        List<ExecutionStep> steps = parseSteps(stepsNode);
        validateDependencies(steps);
        validateAcyclic(steps);
        return new ExecutionPlan(goal, steps);
    }

    private static JsonNode unwrap(JsonNode node) {
        if (node == null) {
            return null;
        }
        JsonNode nested = node.get("plan");
        return nested != null && nested.isObject() ? nested : node;
    }

    private static List<ExecutionStep> parseSteps(JsonNode stepsNode) {
        List<ExecutionStep> steps = new ArrayList<>(stepsNode.size());
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < stepsNode.size(); index++) {
            JsonNode stepNode = stepsNode.get(index);
            if (stepNode == null || !stepNode.isObject()) {
                throw new IllegalArgumentException("第 " + (index + 1) + " 个步骤必须是 JSON 对象");
            }
            String id = text(stepNode, "id");
            if (id == null) {
                throw new IllegalArgumentException("第 " + (index + 1) + " 个步骤缺少非空的 id");
            }
            if (!ids.add(id)) {
                throw new IllegalArgumentException("步骤 id 重复：" + id);
            }
            String title = text(stepNode, "title");
            if (title == null) {
                throw new IllegalArgumentException("步骤 " + id + " 缺少非空的 title");
            }
            steps.add(new ExecutionStep(id, title, text(stepNode, "description"),
                    strings(stepNode.get("dependencies")), text(stepNode, "expectedOutput")));
        }
        return steps;
    }

    private static void validateDependencies(List<ExecutionStep> steps) {
        Set<String> ids = new HashSet<>();
        for (ExecutionStep step : steps) {
            ids.add(step.getId());
        }
        for (ExecutionStep step : steps) {
            for (String dependency : step.getDependencies()) {
                if (dependency.equals(step.getId())) {
                    throw new IllegalArgumentException("步骤 " + step.getId() + " 不能依赖自身");
                }
                if (!ids.contains(dependency)) {
                    throw new IllegalArgumentException(
                            "步骤 " + step.getId() + " 的依赖不存在：" + dependency);
                }
            }
        }
    }

    private static void validateAcyclic(List<ExecutionStep> steps) {
        Map<String, ExecutionStep> byId = new LinkedHashMap<>();
        for (ExecutionStep step : steps) {
            byId.put(step.getId(), step);
        }
        Map<String, Integer> colors = new HashMap<>();
        for (ExecutionStep step : steps) {
            visit(step, byId, colors, new ArrayList<>());
        }
    }

    private static void visit(ExecutionStep step, Map<String, ExecutionStep> byId,
                              Map<String, Integer> colors, List<String> path) {
        Integer color = colors.get(step.getId());
        if (Integer.valueOf(1).equals(color)) {
            path.add(step.getId());
            throw new IllegalArgumentException("计划存在循环依赖：" + String.join(" -> ", path));
        }
        if (Integer.valueOf(2).equals(color)) {
            return;
        }
        colors.put(step.getId(), 1);
        path.add(step.getId());
        for (String dependency : step.getDependencies()) {
            visit(byId.get(dependency), byId, colors, path);
        }
        path.remove(path.size() - 1);
        colors.put(step.getId(), 2);
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return new ArrayList<>();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode element : node) {
            if (element != null && element.isTextual() && !StringUtils.isBlank(element.asText())) {
                values.add(element.asText().trim());
            }
        }
        return values;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() && !StringUtils.isBlank(value.asText()) ? value.asText().trim() : null;
    }
}