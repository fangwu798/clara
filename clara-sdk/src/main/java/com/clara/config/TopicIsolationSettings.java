package com.clara.config;

import com.clara.dto.memory.TopicIsolationConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.Getter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 话题隔离开关：{@code config/clara.yml} 的 {@code context.topicIsolation} 节点。
 *
 * <p>节点缺省时按默认值启用；配置只影响历史消息如何进入本轮请求，不改变已持久化的历史。
 */
@Getter
public final class TopicIsolationSettings {

    private static final String NODE = "context";
    private static final String FIELD = "topicIsolation";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final boolean enabled;
    private final double shiftThreshold;
    private final int carryOverPreviousTurns;
    private final boolean injectArchivedSummary;
    private final int explicitReferenceWindow;

    private TopicIsolationSettings(boolean enabled, double shiftThreshold, int carryOverPreviousTurns,
            boolean injectArchivedSummary, int explicitReferenceWindow) {
        this.enabled = enabled;
        this.shiftThreshold = shiftThreshold;
        this.carryOverPreviousTurns = carryOverPreviousTurns;
        this.injectArchivedSummary = injectArchivedSummary;
        this.explicitReferenceWindow = explicitReferenceWindow;
    }

    /**
     * 读取 {@code config/clara.yml} 的 {@code context.topicIsolation} 节点。
     *
     * @param configFile 本地运行配置文件；为 null 或读不出内容时返回默认配置
     * @return 配置值对象，非 null
     */
    public static TopicIsolationSettings load(Path configFile) {
        if (configFile == null || !Files.isReadable(configFile)) {
            return defaults();
        }
        JsonNode root;
        try {
            root = YAML.readTree(Files.readAllBytes(configFile)).get(NODE);
        } catch (IOException | RuntimeException failure) {
            return defaults();
        }
        if (root == null || !root.isObject()) {
            return defaults();
        }
        JsonNode node = root.get(FIELD);
        if (node == null || !node.isObject()) {
            return defaults();
        }
        return new TopicIsolationSettings(
                bool(node, "enabled", true),
                decimal(node, "shiftThreshold", TopicIsolationConfig.DEFAULT_SHIFT_THRESHOLD),
                integer(node, "carryOverPreviousTurns", 1, 0),
                bool(node, "injectArchivedSummary", false),
                integer(node, "explicitReferenceWindow", 5, 0));
    }

    /**
     * 默认配置：启用话题隔离，明确切换时不携带旧轮次，不注入归档摘要。
     *
     * @return 默认配置，非 null
     */
    public static TopicIsolationSettings defaults() {
        return new TopicIsolationSettings(true, TopicIsolationConfig.DEFAULT_SHIFT_THRESHOLD, 1, false, 5);
    }

    /**
     * 转换为 core 契约的话题隔离配置。
     *
     * @return 话题隔离配置，非 null
     */
    public TopicIsolationConfig toConfig() {
        return new TopicIsolationConfig(enabled, shiftThreshold, carryOverPreviousTurns,
                injectArchivedSummary, explicitReferenceWindow);
    }

    private static boolean bool(JsonNode node, String field, boolean fallback) {
        JsonNode value = node.get(field);
        return value != null && value.isBoolean() ? value.asBoolean() : fallback;
    }

    private static int integer(JsonNode node, String field, int fallback, int minimum) {
        JsonNode value = node.get(field);
        return value != null && value.isInt() && value.asInt() >= minimum ? value.asInt() : fallback;
    }

    private static double decimal(JsonNode node, String field, double fallback) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            return fallback;
        }
        double number = value.asDouble();
        return Double.isNaN(number) || number < 0.0d || number > 1.0d ? fallback : number;
    }
}
