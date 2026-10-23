package com.clara.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.Getter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 代码知识图谱开关：{@code config/clara.yml} 的 {@code codeGraph} 节点。
 *
 * <p>是什么、在哪一层：application 的本地配置值对象（包 {@code com.clara.config}），与模型配置、语义记忆
 * 配置并列——它不驱动图谱本身，只决定「Clara 侧要不要用图谱、用到什么程度」。
 *
 * <p>配置形态（节点缺省即可，缺省等于全部按默认值）：
 * <pre>
 * codeGraph:
 *   enabled: true          # 总开关：false 时其余开关一律不生效（等于没接图谱）
 *   supplement: false      # 每轮对话前先向图谱查一次，命中的知识作为补充信息注入提示词（默认关，见下）
 *   autoMount: true        # 启动时确保图谱容器能看到当前工作目录（不在挂载里就补一条只读挂载）
 *   autoImport: false      # 启动时把当前工作目录导入图谱（未索引则后台发起，不阻塞启动）
 *   mcpServer: codebase-memory-mcp   # 图谱在 config/mcp 里的注册键（仅用于日志与排查）
 *   supplementMaxChars: 4000         # 单轮补充信息的字符上限
 * </pre>
 *
 * <p>未知字段与非法值一律按默认值处理，不因此启动失败：图谱是增强能力，配错了不该挡住对话。
 */
@Getter
public final class CodeGraphSettings {

    /**
     * 配置节点名。
     */
    private static final String NODE = "codeGraph";

    /**
     * 默认 MCP 注册键（与 deploy 的部署脚本写入值一致）。
     */
    private static final String DEFAULT_MCP_SERVER = "codebase-memory-mcp";

    /**
     * 默认补充信息字符上限。
     */
    private static final int DEFAULT_SUPPLEMENT_MAX_CHARS = 4000;

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /**
     * -- GETTER --
     *  总开关是否打开。
     *
     * @return true 表示允许使用图谱能力
     */
    private final boolean enabled;
    private final boolean supplement;
    private final boolean autoMount;
    private final boolean autoImport;
    /**
     * -- GETTER --
     *  图谱在
     *  里的注册键，用于日志与排查。
     *
     * @return 注册键，非 null
     */
    private final String mcpServer;
    /**
     * -- GETTER --
     *  单轮补充信息的字符上限。
     *
     * @return 正整数字符数
     */
    private final int supplementMaxChars;

    private CodeGraphSettings(boolean enabled, boolean supplement, boolean autoMount, boolean autoImport,
            String mcpServer, int supplementMaxChars) {
        this.enabled = enabled;
        this.supplement = supplement;
        this.autoMount = autoMount;
        this.autoImport = autoImport;
        this.mcpServer = mcpServer;
        this.supplementMaxChars = supplementMaxChars;
    }

    /**
     * 读取 {@code config/clara.yml} 的 {@code codeGraph} 节点。
     *
     * @param configFile 本地运行配置文件；为 null 或读不出内容时返回默认配置
     * @return 配置值对象，非 null
     */
    public static CodeGraphSettings load(Path configFile) {
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
        boolean enabled = bool(node, "enabled", true);
        String server = text(node, "mcpServer", DEFAULT_MCP_SERVER);
        int maxChars = integer(node, "supplementMaxChars", DEFAULT_SUPPLEMENT_MAX_CHARS);
        return new CodeGraphSettings(enabled, bool(node, "supplement", false) && enabled,
                bool(node, "autoMount", true) && enabled, bool(node, "autoImport", false) && enabled, server,
                maxChars);
    }

    /**
     * 默认配置：开启图谱、关闭每轮补充信息、启动时按需挂载、不自动导入。
     *
     * @return 默认配置，非 null
     */
    public static CodeGraphSettings defaults() {
        return new CodeGraphSettings(true, false, true, false, DEFAULT_MCP_SERVER, DEFAULT_SUPPLEMENT_MAX_CHARS);
    }

    /**
     * 是否在每轮对话前查询图谱并把命中结果作为补充信息注入提示词。
     *
     * <p>默认关：这笔查询压在每轮模型开始之前，实测大仓库（lippo-life，约 2 万个符号）单次要 20 秒以上
     * （换项目、换关键词都稳定 20 秒左右，不是冷启动；同库按符号名的 {@code search_graph} 只要 3 秒），
     * 会把首字响应从 1~2 秒拖到 20 秒+。模型本来就会按需调 {@code code_graph}
     * （常驻提示词 {@code code_graph_guide} 保证它知道要查），所以默认交给模型按需查询。
     *
     * @return true 表示启用补充信息
     */
    public boolean isSupplementEnabled() {
        return supplement;
    }

    /**
     * 是否在启动时确保图谱容器能看到当前工作目录（不在挂载里就补一条只读挂载）。
     *
     * @return true 表示启用启动挂载
     */
    public boolean isAutoMountEnabled() {
        return autoMount;
    }

    /**
     * 是否在启动时把当前工作目录导入图谱（未索引时后台发起）。
     *
     * @return true 表示启用启动导入
     */
    public boolean isAutoImportEnabled() {
        return autoImport;
    }

    private static boolean bool(JsonNode node, String field, boolean fallback) {
        JsonNode value = node.get(field);
        return value != null && value.isBoolean() ? value.asBoolean() : fallback;
    }

    private static int integer(JsonNode node, String field, int fallback) {
        JsonNode value = node.get(field);
        return value != null && value.isInt() && value.asInt() > 0 ? value.asInt() : fallback;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && !value.asText().trim().isEmpty()
                ? value.asText().trim() : fallback;
    }
}
