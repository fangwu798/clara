package com.clara.dto.planner;

import com.clara.utils.StringUtils;
import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 执行计划中的单个步骤：描述要做什么、依赖哪些步骤以及预期产出。
 *
 * <p>为什么单列一套计划 DTO：任务规划引擎的 {@link TaskNode} 需要绑定处理器、预算与能力，
 * 而这里承载的是模型生成的「轻量执行计划」，只要求可读、可校验、可注入提示词。
 */
@Data
public final class ExecutionStep {

    /** 步骤标识，计划内唯一。 */
    private final String id;

    /** 步骤标题，一句话说明要做什么。 */
    private final String title;

    /** 步骤说明；未提供时退回标题。 */
    private final String description;

    /** 依赖的步骤标识，不可变列表，可能为空。 */
    private final List<String> dependencies;

    /** 预期产出；未提供时为空字符串。 */
    private final String expectedOutput;

    /**
     * 创建执行步骤。
     *
     * @param id             步骤标识，不可为空白
     * @param title          步骤标题，不可为空白
     * @param description    步骤说明，可为空（为空时使用标题）
     * @param dependencies   依赖的步骤标识，可为 null（视为无依赖）
     * @param expectedOutput 预期产出，可为空
     * @throws IllegalArgumentException id 或 title 为空白，或依赖标识为空白时
     */
    public ExecutionStep(String id, String title, String description, List<String> dependencies,
            String expectedOutput) {
        if (StringUtils.isBlank(id) || StringUtils.isBlank(title)) {
            throw new IllegalArgumentException("execution step id and title must not be blank");
        }
        this.id = id.trim();
        this.title = title.trim();
        this.description = StringUtils.isBlank(description) ? this.title : description.trim();
        this.dependencies = copyDependencies(dependencies);
        this.expectedOutput = StringUtils.isBlank(expectedOutput) ? "" : expectedOutput.trim();
    }

    private static List<String> copyDependencies(List<String> dependencies) {
        if (dependencies == null || dependencies.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> copied = new ArrayList<>(dependencies.size());
        for (String dependency : dependencies) {
            if (StringUtils.isBlank(dependency)) {
                throw new IllegalArgumentException("execution step dependency must not be blank");
            }
            copied.add(dependency.trim());
        }
        return Collections.unmodifiableList(copied);
    }
}