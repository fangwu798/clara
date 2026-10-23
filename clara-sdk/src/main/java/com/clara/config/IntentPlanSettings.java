package com.clara.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.Getter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 意图识别与动态计划开关：{@code config/clara.yml} 的 {@code intentPlanning} 节点。
 *
 * <p>开启后，每轮用户输入先判断复杂度：复杂任务生成执行计划并经 {@code validate_execution_plan}
 * 工具校验后注入提示词，简单任务仍由模型直接回答。节点缺省时按默认值启用。
 */
@Getter
public final class IntentPlanSettings {

    private static final String NODE = "intentPlanning";

    private static final boolean DEFAULT_ENABLED = true;
    private static final int DEFAULT_MAX_STEPS = 12;
    private static final int DEFAULT_MAX_REPAIR_ATTEMPTS = 1;

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /**
     * -- GETTER --
     *  是否启用意图识别与动态计划。
     *
     * @return true 表示启用
     */
    private final boolean enabled;
    /**
     * -- GETTER --
     *  计划最大步骤数。
     *
     * @return 正整数的步骤上限
     */
    private final int maxSteps;
    /**
     * -- GETTER --
     *  校验失败后的最大修正次数。
     *
     * @return 非负整数
     */
    private final int maxRepairAttempts;

    private IntentPlanSettings(boolean enabled, int maxSteps, int maxRepairAttempts) {
        this.enabled = enabled;
        this.maxSteps = maxSteps;
        this.maxRepairAttempts = maxRepairAttempts;
    }

    /**
     * 读取 {@code config/clara.yml} 的 {@code intentPlanning} 节点。
     *
     * @param configFile 本地运行配置文件；为 null 或读不出内容时返回默认配置
     * @return 配置值对象，非 null
     */
    public static IntentPlanSettings load(Path configFile) {
        if (configFile == null || !Files.isReadable(configFile)) {
            return defaults();
        }
        JsonNode node;
        try {
            node = YAML.readTree(Files.readAllBytes(configFile)).get(NODE);
        } catch (IOException | RuntimeException failure) {
            return defaults();
        }
        if (node == null || !node.isObject()) {
            return defaults();
        }
        return new IntentPlanSettings(bool(node, "enabled", DEFAULT_ENABLED),
                integer(node, "maxSteps", DEFAULT_MAX_STEPS, 1),
                integer(node, "maxRepairAttempts", DEFAULT_MAX_REPAIR_ATTEMPTS, 0));
    }

    /**
     * 默认配置：启用计划、最多 12 步、最多修正 1 次。
     *
     * @return 默认配置，非 null
     */
    public static IntentPlanSettings defaults() {
        return new IntentPlanSettings(DEFAULT_ENABLED, DEFAULT_MAX_STEPS, DEFAULT_MAX_REPAIR_ATTEMPTS);
    }

    private static boolean bool(JsonNode node, String field, boolean fallback) {
        JsonNode value = node.get(field);
        return value != null && value.isBoolean() ? value.asBoolean() : fallback;
    }

    private static int integer(JsonNode node, String field, int fallback, int minimum) {
        JsonNode value = node.get(field);
        return value != null && value.isInt() && value.asInt() >= minimum ? value.asInt() : fallback;
    }
}