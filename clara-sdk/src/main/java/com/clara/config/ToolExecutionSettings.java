package com.clara.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.Getter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 工具执行超时：{@code config/clara.yml} 的 {@code tools} 节点。
 *
 * <p>宿主对每次脚本工具调用有硬超时，默认 30 秒。像 ComfyUI 参考图上传这类
 * 「耗时取决于本机上行带宽」的操作，可以在这里整体调大；脚本内部仍会按自身
 * 请求超时提前失败，因此调大不会让每次调用都真的等满。
 */
@Getter
public final class ToolExecutionSettings {

    private static final String NODE = "tools";

    private static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;
    private static final long MINIMUM_TIMEOUT_MILLIS = 1_000L;

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /**
     * -- GETTER --
     * 单次脚本工具调用的硬超时（毫秒）。
     *
     * @return 正数毫秒值
     */
    private final long timeoutMillis;

    private ToolExecutionSettings(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * 读取 {@code config/clara.yml} 的 {@code tools} 节点。
     *
     * @param configFile 本地运行配置文件；为 null 或读不出内容时返回默认配置
     * @return 配置值对象，非 null
     */
    public static ToolExecutionSettings load(Path configFile) {
        if (configFile == null || !Files.isReadable(configFile)) {
            return defaults();
        }
        JsonNode node;
        try {
            JsonNode root = YAML.readTree(Files.readAllBytes(configFile));
            node = root == null ? null : root.get(NODE);
        } catch (IOException | RuntimeException failure) {
            return defaults();
        }
        if (node == null || !node.isObject()) {
            return defaults();
        }
        JsonNode value = node.get("timeoutMillis");
        long timeout = value != null && value.canConvertToLong() ? value.asLong() : DEFAULT_TIMEOUT_MILLIS;
        return new ToolExecutionSettings(Math.max(MINIMUM_TIMEOUT_MILLIS, timeout));
    }

    /**
     * 默认配置：30 秒。
     *
     * @return 默认配置，非 null
     */
    public static ToolExecutionSettings defaults() {
        return new ToolExecutionSettings(DEFAULT_TIMEOUT_MILLIS);
    }
}
