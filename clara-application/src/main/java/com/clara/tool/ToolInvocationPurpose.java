package com.clara.tool;

import com.clara.utils.StringUtils;
import com.clara.workflow.WorkflowCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 工具调用用途：把一次工具调用（{@code run_command} / {@code run_workflow} 等）翻成一句中文用途说明。
 *
 * <p>是什么、在哪一层：application 的展示辅助（包 {@code com.clara.tool}），供控制台在工具往返中打印
 * 「这一行在做什么」；入参自带 {@code purpose} 时优先采用模型给的说明。
 *
 * <p>工作流用途：标识到描述的映射交给 {@link com.clara.workflow.WorkflowCatalog} 现扫现取，
 * 因此运行期新生成的自定义工作流同样能显示出描述。
 */
public final class ToolInvocationPurpose {
    private static final String RUN_COMMAND = "run_command";
    private static final String RUN_WORKFLOW = "run_workflow";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> COMMAND_PURPOSES = commandPurposes();

    private ToolInvocationPurpose() {
    }

    public static String describe(String toolName, String argumentsJson, String declaredDescription) {
        JsonNode root = parse(argumentsJson);
        JsonNode explicit = root == null ? null : root.get("purpose");
        if (explicit != null && explicit.isTextual() && !StringUtils.isBlank(explicit.textValue())) {
            return explicit.textValue().trim();
        }
        if (RUN_COMMAND.equals(toolName)) {
            String purpose = commandPurpose(root);
            if (purpose != null) {
                return purpose;
            }
        }
        if (RUN_WORKFLOW.equals(toolName)) {
            String purpose = workflowPurpose(root);
            if (purpose != null) {
                return purpose;
            }
        }
        return declaredDescription;
    }

    private static String commandPurpose(JsonNode root) {
        String command = command(root);
        if (StringUtils.isBlank(command)) {
            return null;
        }
        String purpose = COMMAND_PURPOSES.get(commandKey(command));
        return purpose == null ? commandLine(root) : purpose;
    }

    private static String workflowPurpose(JsonNode root) {
        JsonNode workflow = root == null ? null : root.get("workflow");
        if (workflow == null || !workflow.isTextual() || StringUtils.isBlank(workflow.textValue())) {
            return null;
        }
        String id = workflow.textValue().trim();
        String description = WorkflowCatalog.describe(id);
        return description == null ? "运行工作流 " + id : "运行工作流 " + id + "：" + description;
    }

    private static String command(JsonNode root) {
        if (root == null) {
            return null;
        }
        JsonNode argv = root.get("argv");
        if (argv != null && argv.isArray() && argv.size() > 0 && argv.get(0).isTextual()) {
            return argv.get(0).textValue();
        }
        JsonNode value = root.get("command");
        return value != null && value.isTextual() ? firstToken(value.textValue()) : null;
    }

    private static String firstToken(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) return text;
        if (text.charAt(0) == '\'' || text.charAt(0) == '"') {
            int end = text.indexOf(text.charAt(0), 1);
            return end > 0 ? text.substring(1, end) : text.substring(1);
        }
        int space = text.indexOf(' ');
        return space > 0 ? text.substring(0, space) : text;
    }

    private static String commandKey(String value) {
        String name = value == null ? "" : value.trim();
        int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (separator >= 0) name = name.substring(separator + 1);
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name).toLowerCase(Locale.ROOT);
    }

    private static String commandLine(JsonNode root) {
        JsonNode argv = root == null ? null : root.get("argv");
        return argv != null && argv.isArray() ? joinArguments(argv) : textValue(root);
    }

    private static String joinArguments(JsonNode argv) {
        StringBuilder line = new StringBuilder();
        for (JsonNode item : argv) {
            appendArgument(line, item);
        }
        return line.toString();
    }

    private static void appendArgument(StringBuilder line, JsonNode item) {
        if (item != null && item.isTextual()) {
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(item.textValue());
        }
    }

    private static String textValue(JsonNode root) {
        JsonNode command = root == null ? null : root.get("command");
        return command != null && command.isTextual() ? command.textValue() : null;
    }

    private static Map<String, String> commandPurposes() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("java", "运行 Java 程序");
        result.put("javac", "编译 Java 源代码");
        result.put("mvn", "构建 Maven 项目");
        result.put("git", "执行 Git 版本控制操作");
        result.put("python", "运行 Python 脚本");
        result.put("python3", "运行 Python 脚本");
        result.put("pytest", "运行 Python 测试");
        result.put("npm", "运行前端脚本或管理依赖");
        result.put("docker", "操作容器");
        result.put("curl", "发起 HTTP 请求");
        result.put("cat", "查看文件内容");
        result.put("type", "查看文件内容");
        return Collections.unmodifiableMap(result);
    }

    private static JsonNode parse(String value) {
        if (StringUtils.isBlank(value)) return null;
        try { return JSON.readTree(value); } catch (IOException ignored) { return null; }
    }

}
