package com.clara.dto.planner;

import com.clara.utils.StringUtils;
import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 模型生成的轻量执行计划：目标 + 有序步骤，用于「先规划再执行」的对话轮次。
 *
 * <p>与 {@link TaskPlan} 的区别：本类不绑定任务处理器、预算与能力授权，只承载可注入提示词的步骤说明；
 * 结构合法性由 {@code ExecutionPlanValidator} 校验，并通过内置工具 {@code validate_execution_plan} 暴露给模型。
 */
@Data
public final class ExecutionPlan {

    /** 计划目标，不可为空白。 */
    private final String goal;

    /** 执行步骤，不可变列表，至少一步。 */
    private final List<ExecutionStep> steps;

    /**
     * 创建执行计划。
     *
     * @param goal  计划目标，不可为空白
     * @param steps 执行步骤，不可为 null 或空，步骤标识必须唯一
     * @throws IllegalArgumentException 目标为空、步骤为空或步骤标识重复时
     */
    public ExecutionPlan(String goal, List<ExecutionStep> steps) {
        if (StringUtils.isBlank(goal) || steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("execution plan goal and steps must not be empty");
        }
        Set<String> ids = new HashSet<>();
        List<ExecutionStep> copied = new ArrayList<>(steps.size());
        for (ExecutionStep step : steps) {
            if (step == null || !ids.add(step.getId())) {
                throw new IllegalArgumentException("execution plan step ids must be unique");
            }
            copied.add(step);
        }
        this.goal = goal.trim();
        this.steps = Collections.unmodifiableList(copied);
    }

    /**
     * 渲染为注入 system 指令的执行计划文本。
     *
     * @return 可直接拼进提示词的 Markdown 文本，非 null
     */
    public String toPrompt() {
        StringBuilder text = new StringBuilder();
        text.append("## 本轮执行计划（已通过格式校验）\n");
        text.append("目标：").append(goal).append('\n');
        for (int index = 0; index < steps.size(); index++) {
            ExecutionStep step = steps.get(index);
            text.append(index + 1).append(". [").append(step.getId()).append("] ")
                    .append(step.getTitle()).append('\n');
            text.append("   说明：").append(step.getDescription()).append('\n');
            text.append("   依赖：").append(step.getDependencies().isEmpty()
                    ? "无" : String.join(", ", step.getDependencies())).append('\n');
            if (!step.getExpectedOutput().isEmpty()) {
                text.append("   预期产出：").append(step.getExpectedOutput()).append('\n');
            }
        }
        text.append("执行要求：严格按上述步骤推进；每完成一步用工具确认结果，")
                .append("某一步失败时说明原因并调整后续步骤；最终回答需汇总各步产出。\n");
        return text.toString();
    }
}