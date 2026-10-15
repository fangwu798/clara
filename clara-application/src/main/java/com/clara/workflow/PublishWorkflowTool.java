package com.clara.workflow;

import com.clara.api.IBuiltinTool;
import com.clara.dto.workflow.WorkflowDefinition;
import com.clara.workflow.definition.WorkflowDefinitionRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 内置工具 {@code publish_workflow}：校验模型生成的工作流定义并发布到 {@code config/workflow/custom}。
 *
 * <p>是什么、在哪一层：application 工作流装配层（包 {@code com.clara.workflow}）对 {@link IBuiltinTool}
 * 的实现，与 {@link WorkflowRunTool} 同处内置工具层；「生成自定义工作流」的工作流用它在会话中落盘，
 * 用户也可直接让模型调用它来改造已有流程。
 *
 * <p>为什么先校验再落盘：解析走的是运行期同一个 {@link WorkflowDefinitionRegistry} 加载器，因此「发布成功」
 * 等价于「重新加载时一定能被解析」；非法定义直接以加载器的报错（含节点 id）回传，模型据此修正后重试，
 * 不会把半成品写进配置目录、也不会让坏文件拖垮其它工作流。
 *
 * <p>立即生效：发布成功后调用 {@code registry.refresh()}，同一会话里随后即可按标识运行，
 * 无需重启进程。
 *
 * <p>入参 JSON：{@code yaml}（必填，工作流定义 YAML 文本）、{@code dryRun}（可选布尔，true 表示只校验不落盘）。
 * 失败语义：缺 {@code yaml}、定义非法、标识命名不合法或与其它工作流重名时抛
 * {@link IllegalArgumentException}；文件读写失败抛 {@link IllegalStateException}。
 */
public final class PublishWorkflowTool implements IBuiltinTool {

    /**
     * 工具名：与 {@code config/skill/tools/builtin/publish_workflow.json} 的声明保持一致。
     */
    private static final String NAME = "publish_workflow";

    /**
     * 自定义工作流子目录名。
     */
    private static final String CUSTOM_DIRECTORY = "custom";

    /**
     * 工作流标识允许的字符：与文件名一一对应，避免出现需要转义的路径。
     */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final WorkflowDefinitionRegistry registry;

    /**
     * 创建内置工具。
     *
     * @param registry 工作流定义注册表（同时提供加载器与定义目录），不可为 null
     * @throws IllegalArgumentException registry 为 null 时
     */
    public PublishWorkflowTool(WorkflowDefinitionRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        this.registry = registry;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String execute(String argumentsJson) {
        JsonNode root = parse(argumentsJson);
        String yaml = text(root, "yaml");
        if (yaml == null) {
            throw new IllegalArgumentException("publish_workflow 缺少 yaml 参数（工作流定义的 YAML 文本）");
        }
        boolean dryRun = root.path("dryRun").asBoolean(false);
        Path customDirectory = customDirectory();
        Path temporary = writeTemporary(yaml);
        try {
            WorkflowDefinition definition = definition(customDirectory, temporary);
            String workflowId = definition.getWorkflowId();
            if (!SAFE_ID.matcher(workflowId).matches()) {
                throw new IllegalArgumentException("工作流标识只允许字母 / 数字 / 下划线 / 中划线，且不超过 64 个字符，"
                        + "请修改 workflowId 后重试：" + workflowId);
            }
            Path existing = existing(customDirectory, workflowId);
            if (existing == null && registry.ids().contains(workflowId)) {
                throw new IllegalArgumentException("工作流标识已被其它工作流占用，请换一个标识：" + workflowId);
            }
            Path target = existing == null ? customDirectory.resolve(workflowId + ".yml") : existing;
            if (!dryRun) {
                move(temporary, target);
                temporary = null;
                // 发布即生效：刷新定义注册表，同一会话后续即可按标识运行
                registry.refresh();
            }
            return result(workflowId, target, existing != null, dryRun);
        } finally {
            deleteQuietly(temporary);
        }
    }

    /**
     * 解析工作流定义；非法时把加载器报错里的临时文件路径换成「待发布的自定义工作流」，便于模型定位问题。
     */
    private WorkflowDefinition definition(Path customDirectory, Path temporary) {
        try {
            return registry.parseDefinition(temporary);
        } catch (IllegalArgumentException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            throw new IllegalArgumentException(message.replace(temporary.toString(), "待发布的自定义工作流")
                    + "（定义目录：" + display(customDirectory) + "）", e);
        }
    }

    /**
     * 定位并创建自定义工作流目录。
     *
     * @return 自定义工作流目录，非 null
     * @throws IllegalStateException 未配置工作流定义目录或目录不可创建时
     */
    private Path customDirectory() {
        Path directory = registry.directory();
        if (directory == null) {
            throw new IllegalStateException("未配置工作流定义目录，无法发布自定义工作流");
        }
        Path custom = directory.resolve(CUSTOM_DIRECTORY);
        try {
            Files.createDirectories(custom);
        } catch (IOException e) {
            throw new IllegalStateException("自定义工作流目录不可创建（" + display(custom) + "）：" + e.getMessage(), e);
        }
        return custom;
    }

    /**
     * 把待发布的 YAML 写入系统临时目录下的临时文件：先校验、通过后再移动到配置目录，避免半个文件被加载。
     */
    private static Path writeTemporary(String yaml) {
        try {
            Path temporary = Files.createTempFile("clara-workflow-", ".yml");
            Files.write(temporary, (yaml.trim() + "\n").getBytes(StandardCharsets.UTF_8));
            return temporary;
        } catch (IOException e) {
            throw new IllegalStateException("工作流定义临时文件写入失败：" + e.getMessage(), e);
        }
    }

    /**
     * 取自定义目录下已有的同名定义文件（{@code .yml} / {@code .yaml}）。
     *
     * @return 已存在的文件，不存在时返回 null
     */
    private static Path existing(Path customDirectory, String workflowId) {
        Path yml = customDirectory.resolve(workflowId + ".yml");
        if (Files.isRegularFile(yml)) {
            return yml;
        }
        Path yaml = customDirectory.resolve(workflowId + ".yaml");
        return Files.isRegularFile(yaml) ? yaml : null;
    }

    private static void move(Path temporary, Path target) {
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("自定义工作流写入失败（" + display(target) + "）：" + e.getMessage(), e);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 临时文件删除失败不影响本次发布结果
        }
    }

    /**
     * 组装回执：标识、落盘路径与「怎么调用」提示，供模型直接转述给用户。
     */
    private static String result(String workflowId, Path target, boolean replaced, boolean dryRun) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("workflowId", workflowId);
        payload.put("path", display(target));
        payload.put("replaced", replaced);
        payload.put("dryRun", dryRun);
        payload.put("status", dryRun ? "校验通过（未落盘）" : "已发布并立即生效");
        payload.put("usage", "调用方式：run_workflow，workflow=" + workflowId + "；后续会话无需重启即可使用");
        try {
            return JSON.writeValueAsString(payload);
        } catch (IOException e) {
            throw new IllegalStateException("发布结果序列化失败：" + e.getMessage(), e);
        }
    }

    private static String display(Path path) {
        return path.toString().replace(File.separatorChar, '/');
    }

    private static JsonNode parse(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.trim().isEmpty()) {
            throw new IllegalArgumentException("publish_workflow 缺少入参");
        }
        try {
            return JSON.readTree(argumentsJson);
        } catch (IOException e) {
            throw new IllegalArgumentException("publish_workflow 入参不是合法 JSON：" + e.getMessage(), e);
        }
    }

    /**
     * 读取文本参数；缺失、非字符串或空白时返回 null。
     */
    private static String text(JsonNode root, String field) {
        JsonNode value = root == null ? null : root.get(field);
        if (value == null || !value.isTextual() || value.textValue().trim().isEmpty()) {
            return null;
        }
        return value.textValue().trim();
    }
}
