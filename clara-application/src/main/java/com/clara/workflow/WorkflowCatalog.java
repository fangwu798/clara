package com.clara.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

/**
 * 工作流清单：只读扫描 {@code config/workflow} 下的 YAML 定义，给出「标识 → 描述」与可下发给模型的清单文本。
 *
 * <p>是什么、在哪一层：application 的工作流展示辅助（包 {@code com.clara.workflow}），不参与执行；供两处使用：
 * 其一，把「当前可用工作流」拼进内置工具 {@code run_workflow} 的描述，模型据此按名调用；其二，调用记录中
 * 展示「运行工作流 xxx：描述」。
 *
 * <p>为什么每次重新扫描：自定义工作流由模型在会话中写入 {@code config/workflow/custom}，清单必须随文件变化
 * 立即生效，因此不做进程内缓存（定义文件数量少，一次目录遍历 + 解析的成本可忽略）。
 *
 * <p>失败语义：目录不存在、文件不可读或缺少 {@code workflowId} 时跳过该文件，不抛异常——清单只是辅助信息，
 * 不能因为一个坏文件影响对话与执行。
 */
public final class WorkflowCatalog {

    /**
     * 工作流定义目录（相对运行目录）。
     */
    private static final Path WORKFLOW_DIRECTORY = Paths.get("config", "workflow");

    /**
     * 自定义工作流子目录名：由模型生成的工作流写在这里，与随应用发布的内置流程区分开。
     */
    private static final String CUSTOM_DIRECTORY = "custom";

    /**
     * 需要拼接工作流清单的工具名：内置工具 {@code run_workflow} 的入参正是工作流标识。
     */
    private static final String RUN_WORKFLOW_TOOL = "run_workflow";

    /**
     * 自当前目录向上回溯定位定义目录的最大层数（兼容从模块子目录启动）。
     */
    private static final int MAX_SEARCH_DEPTH = 5;

    /**
     * YAML 读取器：只取 {@code workflowId} 与 {@code description} 两个标量字段。
     */
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private WorkflowCatalog() {
    }

    /**
     * 定位工作流定义目录。
     *
     * <p>优先按启动脚本传入的 {@code CLARA_WORK_DIR}（用户执行命令时所在目录）解析，与工作流引擎的定位口径
     * 保持一致；未设置时自当前工作目录逐级向上回溯。
     *
     * @return 定义目录，找不到时返回相对路径本身，非 null
     */
    public static Path directory() {
        String configuredRoot = System.getenv("CLARA_WORK_DIR");
        if (configuredRoot != null && !configuredRoot.trim().isEmpty()) {
            return Paths.get(configuredRoot).resolve(WORKFLOW_DIRECTORY);
        }
        Path current = Paths.get(".").toAbsolutePath().normalize();
        for (int depth = 0; depth < MAX_SEARCH_DEPTH && current != null; depth++) {
            Path candidate = current.resolve(WORKFLOW_DIRECTORY);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        return WORKFLOW_DIRECTORY;
    }

    /**
     * 读取全部工作流的「标识 → 描述」，按标识升序。
     *
     * @return 标识到描述的映射，描述缺失时值为空串；非 null
     */
    public static Map<String, String> descriptions() {
        Map<String, String> result = new LinkedHashMap<>();
        List<Entry> entries = new ArrayList<>(scan());
        entries.sort(Comparator.comparing(left -> left.id));
        for (Entry entry : entries) {
            result.put(entry.id, entry.description == null ? "" : entry.description);
        }
        return result;
    }

    /**
     * 取某个工作流的描述。
     *
     * @param workflowId 工作流标识，可为 null
     * @return 描述文本，未命中或未声明时为 null
     */
    public static String describe(String workflowId) {
        if (workflowId == null || workflowId.trim().isEmpty()) {
            return null;
        }
        for (Entry entry : scan()) {
            if (workflowId.trim().equals(entry.id)) {
                return entry.description;
            }
        }
        return null;
    }

    /**
     * 列出自定义工作流（{@code config/workflow/custom} 下）的「标识（描述）」文本行，按标识升序。
     *
     * @return 文本行列表，非 null，可为空
     */
    public static List<String> customSummaries() {
        List<Entry> entries = new ArrayList<>();
        for (Entry entry : scan()) {
            if (entry.custom) {
                entries.add(entry);
            }
        }
        entries.sort(Comparator.comparing(left -> left.id));
        List<String> lines = new ArrayList<>();
        for (Entry entry : entries) {
            lines.add(entry.label());
        }
        return lines;
    }

    /**
     * 拼一句面向模型的工作流清单：内置与自定义分组列出「标识（描述）」，供工具描述动态拼接。
     *
     * @return 清单文本，无可用工作流时返回空串，非 null
     */
    public static String catalogText() {
        List<Entry> entries = new ArrayList<>(scan());
        if (entries.isEmpty()) {
            return "";
        }
        entries.sort(Comparator.comparing(left -> left.id));
        List<String> builtin = new ArrayList<>();
        List<String> custom = new ArrayList<>();
        for (Entry entry : entries) {
            (entry.custom ? custom : builtin).add(entry.label());
        }
        StringBuilder text = new StringBuilder();
        if (!builtin.isEmpty()) {
            text.append("内置工作流：").append(String.join("、", builtin));
        }
        if (!custom.isEmpty()) {
            if (text.length() > 0) {
                text.append("；");
            }
            text.append("自定义工作流（config/workflow/").append(CUSTOM_DIRECTORY)
                    .append(" 下，写入即可用）：").append(String.join("、", custom));
        }
        return text.toString();
    }

    /**
     * 装饰工具描述：给 {@code run_workflow} 追加「当前可用工作流」清单，其余工具原样返回。
     *
     * <p>用途：作为 {@code IToolDescriptionDecorator} 的实现下发给工具目录，让模型每次请求都看到最新的
     * 工作流清单（含运行期新生成的自定义工作流），从而在用户提到「用某个流程」时能按名调用。
     *
     * @param toolName    工具名，可为 null
     * @param description 工具声明的原始描述，可为 null
     * @return 装饰后的描述；无清单或非目标工具时返回原描述
     */
    public static String decorateToolDescription(String toolName, String description) {
        String original = description == null ? "" : description;
        if (!RUN_WORKFLOW_TOOL.equals(toolName)) {
            return description;
        }
        String catalog = catalogText();
        return catalog.isEmpty() ? description : original + " 当前可用：" + catalog;
    }

    /**
     * 扫描定义目录下的全部工作流文件，跳过不可读或缺少标识的文件。
     */
    private static List<Entry> scan() {
        Path directory = directory();
        if (directory == null || !Files.isDirectory(directory)) {
            return Collections.emptyList();
        }
        List<Entry> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(directory)) {
            stream.filter(Files::isRegularFile).filter(WorkflowCatalog::isWorkflowFile)
                    .sorted().forEach(file -> collect(entries, file, directory));
        } catch (IOException ignored) {
            return Collections.emptyList();
        }
        return entries;
    }

    /**
     * 解析单个定义文件的标识与描述并追加到清单；文件不可读或缺少标识时直接跳过。
     */
    private static void collect(List<Entry> entries, Path file, Path directory) {
        try {
            JsonNode root = YAML.readTree(Files.readAllBytes(file));
            String id = text(root, "workflowId");
            if (id == null) {
                return;
            }
            Path relative = directory.relativize(file);
            boolean custom = relative.getNameCount() > 1
                    && CUSTOM_DIRECTORY.equals(relative.getName(0).toString());
            entries.add(new Entry(id, text(root, "description"), custom,
                    requiredInputs(root.get("inputSchema"))));
        } catch (IOException ignored) {
            // 清单只是辅助信息：单个文件解析失败不能影响其余工作流的展示
        }
    }

    /**
     * 取输入 Schema 里声明的必填入参名，逗号分隔。
     *
     * <p>schema 允许写成 JSON 文本或 YAML 对象两种形式，难以解析时返回 null（清单只是辅助信息，
     * 不因 schema 写法差异报错）；调用方据此把「这次调用该传什么」一并告知模型。
     */
    private static String requiredInputs(JsonNode schema) {
        if (schema == null || schema.isNull()) {
            return null;
        }
        JsonNode parsed = schema;
        if (schema.isTextual()) {
            try {
                parsed = YAML.readTree(schema.textValue());
            } catch (IOException e) {
                return null;
            }
        }
        JsonNode required = parsed == null ? null : parsed.get("required");
        if (required == null || !required.isArray()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (JsonNode item : required) {
            if (item.isTextual() && !item.textValue().trim().isEmpty()) {
                names.add(item.textValue().trim());
            }
        }
        return names.isEmpty() ? null : String.join(", ", names);
    }

    private static boolean isWorkflowFile(Path file) {
        Path fileName = file.getFileName();
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || value.textValue().trim().isEmpty()) {
            return null;
        }
        return value.textValue().trim();
    }

    /**
     * 清单条目：工作流标识、描述、必填入参与是否来自自定义目录。
     */
    private static final class Entry {

        private final String id;
        private final String description;
        private final boolean custom;
        private final String inputs;

        private Entry(String id, String description, boolean custom, String inputs) {
            this.id = id;
            this.description = description;
            this.custom = custom;
            this.inputs = inputs;
        }

        /**
         * 拼一行「标识（描述；必填入参：…）」；未声明描述 / 入参时只留已有部分。
         */
        private String label() {
            StringBuilder text = new StringBuilder(id);
            if (description == null && inputs == null) {
                return text.toString();
            }
            text.append("（");
            if (description != null) {
                text.append(description);
                if (inputs != null) {
                    text.append("；");
                }
            }
            if (inputs != null) {
                text.append("必填入参：").append(inputs);
            }
            return text.append("）").toString();
        }
    }
}
