package com.clara.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 工具调用的用途推导：为「调用工具」日志生成一句人可读的用途说明。
 *
 * <p>是什么、在哪一层：application 装配层（包 {@code com.clara.tool}）的纯函数工具，由
 * {@code ApplicationBootstrap} 提供给控制台使用。
 *
 * <p>为什么不能只看工具声明：一份声明覆盖多种用法。以 {@code run_command} 为例，声明写的是「运行本地命令」，
 * 而这次调用可能是「编译 Java 源代码」、也可能只是「查看 Git 改动」——只有结合本次入参才说得清「为了什么」。
 *
 * <p>取值优先级：
 * <ol>
 *   <li>模型在入参里显式声明的 {@code purpose}（最准确，适用于任意工具）；</li>
 *   <li>若工具是 {@code run_command}，按本次实际命令（{@code argv} 首项或 {@code command} 首个词）映射为一句
 *       用途；识别不出时退回命令原文；</li>
 *   <li>若工具是 {@code run_workflow}，按本次 {@code workflow} 入参给出「运行哪个工作流 + 它的作用」；</li>
 *   <li>退回工具声明里的描述。</li>
 * </ol>
 *
 * <p>失败语义：入参为 null、非 JSON 或非对象时一律视为「取不到」，逐级回退，不抛异常。
 */
public final class ToolInvocationPurpose {

    /**
     * 「运行本地命令」的工具名：其用途取决于本次实际执行的命令。
     */
    private static final String RUN_COMMAND_TOOL = "run_command";

    /**
     * 「运行工作流」的工具名：其用途取决于本次运行的工作流。
     */
    private static final String RUN_WORKFLOW_TOOL = "run_workflow";

    /**
     * 入参中模型声明本次调用目的的字段名。
     */
    private static final String PURPOSE_FIELD = "purpose";

    /**
     * {@code run_workflow} 入参中工作流标识的字段名。
     */
    private static final String WORKFLOW_FIELD = "workflow";

    /**
     * 命令原文兜底展示的最大字符数，超出以省略号截断。
     */
    private static final int COMMAND_MAX_CHARS = 60;

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 常见命令（命令行首个词，小写、不含扩展名）→ 一句用途说明。
     */
    private static final Map<String, String> COMMAND_PURPOSES = commandPurposes();

    /**
     * 内置/随应用发布的工作流标识 → 一句作用说明（与 {@code config/workflow} 下的定义对应）。
     */
    private static final Map<String, String> WORKFLOW_PURPOSES = workflowPurposes();

    private ToolInvocationPurpose() {
    }

    /**
     * 推导一次工具调用的用途说明。
     *
     * @param toolName            工具名，可为 null
     * @param argumentsJson       本次调用的入参 JSON，可为 null
     * @param declaredDescription 工具声明里的描述，可为 null
     * @return 一句用途说明；三处都取不到时返回 null
     */
    public static String describe(String toolName, String argumentsJson, String declaredDescription) {
        String declared = declaredPurpose(argumentsJson);
        if (!blank(declared)) {
            return declared.trim();
        }
        String name = toolName == null ? null : toolName.trim();
        if (RUN_COMMAND_TOOL.equals(name)) {
            String command = commandPurpose(argumentsJson);
            if (!blank(command)) {
                return command;
            }
        }
        if (RUN_WORKFLOW_TOOL.equals(name)) {
            String workflow = workflowPurpose(argumentsJson);
            if (!blank(workflow)) {
                return workflow;
            }
        }
        return declaredDescription;
    }

    /**
     * 按 {@code run_workflow} 的 {@code workflow} 入参推导用途：「运行工作流 &lt;标识&gt;（&lt;作用&gt;）」；
     * 标识不在已知表中时只回显标识。
     *
     * @param argumentsJson 入参 JSON，可为 null
     * @return 用途说明；入参里没有工作流标识时返回 null
     */
    private static String workflowPurpose(String argumentsJson) {
        JsonNode root = parse(argumentsJson);
        JsonNode node = root == null ? null : root.get(WORKFLOW_FIELD);
        if (node == null || !node.isTextual() || blank(node.textValue())) {
            return null;
        }
        String workflowId = node.textValue().trim();
        String purpose = WORKFLOW_PURPOSES.get(workflowId);
        return purpose == null ? "运行工作流 " + workflowId : "运行工作流 " + workflowId + "：" + purpose;
    }

    /**
     * 取入参中模型显式声明的 purpose。
     *
     * @param argumentsJson 入参 JSON，可为 null
     * @return 声明内容；无声明或不可解析时返回 null
     */
    private static String declaredPurpose(String argumentsJson) {
        JsonNode root = parse(argumentsJson);
        JsonNode purpose = root == null ? null : root.get(PURPOSE_FIELD);
        return purpose != null && purpose.isTextual() ? purpose.textValue() : null;
    }

    /**
     * 按本次实际命令推导用途：优先查常见命令表，查不到时退回命令原文（便于用户核对到底执行了什么）。
     *
     * @param argumentsJson 入参 JSON，可为 null
     * @return 用途说明；入参里没有命令时返回 null
     */
    private static String commandPurpose(String argumentsJson) {
        JsonNode root = parse(argumentsJson);
        if (root == null) {
            return null;
        }
        String line = commandLine(root);
        if (blank(line)) {
            return null;
        }
        String mapped = COMMAND_PURPOSES.get(commandKey(executableToken(root, line)));
        return mapped != null ? mapped : truncate(line.trim());
    }

    /**
     * 取用于查表的可执行文件：{@code argv} 存在时首项就是可执行文件本身（路径可含空格，无需拆分）；
     * 否则从命令行文本里取首个词（支持引号包裹）。
     */
    private static String executableToken(JsonNode root, String line) {
        JsonNode argv = root.get("argv");
        if (argv != null && argv.isArray() && argv.size() > 0) {
            JsonNode first = argv.get(0);
            if (first != null && first.isTextual() && !first.textValue().trim().isEmpty()) {
                return first.textValue().trim();
            }
        }
        return firstToken(line);
    }

    /**
     * 取本次命令的展示文本：{@code argv} 优先（与执行器的取值顺序一致），否则取 {@code command}。
     */
    private static String commandLine(JsonNode root) {
        if (root == null) {
            return null;
        }
        JsonNode argv = root.get("argv");
        if (argv != null && argv.isArray() && argv.size() > 0) {
            StringBuilder line = new StringBuilder();
            for (JsonNode item : argv) {
                if (item == null || !item.isTextual()) {
                    continue;
                }
                if (line.length() > 0) {
                    line.append(' ');
                }
                line.append(item.textValue());
            }
            if (line.length() > 0) {
                return line.toString();
            }
        }
        JsonNode command = root.get("command");
        return command != null && command.isTextual() ? command.textValue() : null;
    }

    /**
     * 取命令行首个词：支持被单/双引号包裹的可执行文件路径（如 {@code "C:\Program Files\...\javac.exe"}）。
     */
    private static String firstToken(String line) {
        String text = line.trim();
        if (text.isEmpty()) {
            return text;
        }
        char quote = text.charAt(0);
        if (quote == '"' || quote == '\'') {
            int end = text.indexOf(quote, 1);
            return end > 1 ? text.substring(1, end) : text.substring(1);
        }
        int space = text.indexOf(' ');
        return space > 0 ? text.substring(0, space) : text;
    }

    /**
     * 把可执行文件路径归一为查表键：取文件名、去扩展名、转小写。
     */
    private static String commandKey(String token) {
        String name = token == null ? "" : token.trim();
        int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (separator >= 0) {
            name = name.substring(separator + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name.toLowerCase(Locale.ROOT);
    }

    private static JsonNode parse(String argumentsJson) {
        if (blank(argumentsJson)) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(argumentsJson);
            return root != null && root.isObject() ? root : null;
        } catch (IOException exception) {
            return null;
        }
    }

    private static String truncate(String text) {
        return text.length() <= COMMAND_MAX_CHARS
                ? text
                : text.substring(0, COMMAND_MAX_CHARS) + "…";
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * 常见开发命令的用途表。只覆盖高频命令，其余由「命令原文」兜底，避免维护一份永远不全的字典。
     */
    private static Map<String, String> commandPurposes() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("javac", "编译 Java 源代码");
        map.put("javadoc", "生成 Java API 文档");
        map.put("jar", "打包或查看 Java 归档");
        map.put("java", "运行 Java 程序");
        map.put("mvn", "构建 Maven 项目（编译 / 测试 / 打包）");
        map.put("mvnw", "构建 Maven 项目（编译 / 测试 / 打包）");
        map.put("gradle", "构建 Gradle 项目");
        map.put("gradlew", "构建 Gradle 项目");
        map.put("git", "执行 Git 版本控制操作");
        map.put("npm", "管理前端依赖或执行前端脚本");
        map.put("yarn", "管理前端依赖或执行前端脚本");
        map.put("pnpm", "管理前端依赖或执行前端脚本");
        map.put("node", "运行 Node.js 脚本");
        map.put("python", "运行 Python 脚本");
        map.put("python3", "运行 Python 脚本");
        map.put("py", "运行 Python 脚本");
        map.put("pip", "管理 Python 依赖");
        map.put("pip3", "管理 Python 依赖");
        map.put("pytest", "运行 Python 测试");
        map.put("docker", "操作容器");
        map.put("curl", "发起 HTTP 请求");
        map.put("wget", "下载远端资源");
        map.put("dir", "列出目录内容");
        map.put("ls", "列出目录内容");
        map.put("get-childitem", "列出目录内容");
        map.put("type", "查看文件内容");
        map.put("cat", "查看文件内容");
        map.put("get-content", "查看文件内容");
        map.put("findstr", "在文件中检索文本");
        map.put("select-string", "在文件中检索文本");
        map.put("grep", "在文件中检索文本");
        map.put("mkdir", "创建目录");
        map.put("copy", "复制文件");
        map.put("xcopy", "复制文件或目录");
        map.put("copy-item", "复制文件");
        map.put("del", "删除文件");
        map.put("rm", "删除文件");
        map.put("remove-item", "删除文件或目录");
        map.put("taskkill", "结束进程");
        map.put("stop-process", "结束进程");
        return Collections.unmodifiableMap(map);
    }

    /**
     * 工作流的用途表。只覆盖随应用发布的高频工作流，其余由「运行工作流 &lt;标识&gt;」兜底；
     * 新增工作流时在此补充一句作用说明即可。
     */
    private static Map<String, String> workflowPurposes() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("analyze_repo_docs", "读取当前目录源码并整理技术文档");
        map.put("design_business_solution", "产出《需求与技术方案》");
        map.put("plan_development", "产出《开发计划》");
        map.put("image_generation", "文生图，按主题生成图片");
        map.put("image_edit", "图生图，按参考图编辑");
        map.put("image_understanding", "图片识别与信息提取");
        map.put("review_panel", "多角色评审");
        return Collections.unmodifiableMap(map);
    }
}
