package com.clara.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 常驻提示词开关：{@code config/clara.yml} 的 {@code context} 节点。
 *
 * <p>是什么、在哪一层：application 的本地配置值对象（包 {@code com.clara.config}），与图谱开关并列——
 * 它决定「哪些提示词每轮原样进 system 指令」，也就是不参与按输入的相似度检索。
 *
 * <p>为什么单列一条通道：技能提示词是按用户输入的向量相似度检索注入的（有相似度阈值与条数上限），
 * 像「读代码先查知识图谱」这种与具体输入无关的固定约定，靠检索会出现有时注入、有时不注入，模型行为
 * 随之漂移。固定的约定走这里，随输入变化的技能提示词仍走检索。
 *
 * <p>配置形态（节点缺省即可，缺省等于默认值）：
 * <pre>
 * context:
 *   alwaysPrompts: [code_graph_guide]   # config/skill/prompt 下的提示词标识；写 [] 表示不常驻任何提示词
 * </pre>
 *
 * <p>未知字段与非法值一律按默认值处理，不因此启动失败：提示词是增强能力，配错了不该挡住对话。
 */
public final class AlwaysPromptSettings {

    /**
     * 配置节点名。
     */
    private static final String NODE = "context";

    /**
     * 配置字段名。
     */
    private static final String FIELD = "alwaysPrompts";

    /**
     * 图谱使用约定的提示词标识：默认常驻，图谱关掉时由装配侧跳过。
     */
    public static final String CODE_GRAPH_GUIDE = "code_graph_guide";

    /**
     * 默认常驻提示词：只放图谱使用约定。
     */
    private static final List<String> DEFAULT_IDS =
            Collections.unmodifiableList(Arrays.asList(CODE_GRAPH_GUIDE));

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final List<String> ids;

    private AlwaysPromptSettings(List<String> ids) {
        this.ids = Collections.unmodifiableList(new ArrayList<String>(ids));
    }

    /**
     * 读取 {@code config/clara.yml} 的 {@code context.alwaysPrompts}。
     *
     * <p>字段缺失或不是列表时按默认值处理；写成 {@code []} 是显式要求「不常驻任何提示词」，按空列表返回。
     *
     * @param configFile 本地运行配置文件；为 null 或读不出内容时返回默认配置
     * @return 配置值对象，非 null
     */
    public static AlwaysPromptSettings load(Path configFile) {
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
        JsonNode value = node.get(FIELD);
        if (value == null || !value.isArray()) {
            return defaults();
        }
        List<String> ids = new ArrayList<String>();
        for (JsonNode element : value) {
            if (element != null && element.isTextual() && !element.asText().trim().isEmpty()) {
                ids.add(element.asText().trim());
            }
        }
        return new AlwaysPromptSettings(ids);
    }

    /**
     * 默认配置：只常驻图谱使用约定。
     *
     * @return 默认配置，非 null
     */
    public static AlwaysPromptSettings defaults() {
        return new AlwaysPromptSettings(DEFAULT_IDS);
    }

    /**
     * 常驻提示词标识列表。
     *
     * @return 不可变列表，可能为空，非 null
     */
    public List<String> getIds() {
        return ids;
    }
}
