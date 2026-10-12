package com.clara.skill.registry;

import com.clara.dto.skill.ToolMethod;
import com.clara.enums.skill.ScriptTypeEnum;
import com.clara.skill.tool.script.PythonScriptExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 脚本工具加载器：执行 {@code python <script> --describe}，从脚本输出解析工具声明。
 *
 * <p>是什么、在哪一层：clara-skill 注册表加载链路的一部分，服务于「工具定义（名称/描述/入参 Schema）
 * 与执行体同处一个 {@code .py}」的自描述脚本；与解析 JSON 声明的 {@link SkillLoader} 并列。
 *
 * <p>协议：脚本以 {@code --describe} 调用时，在 stdout 打印一段 JSON（{@code id / name / type /
 * description / parameters / metadata}）后退出；脚本引用（{@code script}）由本加载器按文件路径自动填入。
 * 非工具脚本（如下划线开头的共享模块）正常退出但无输出，返回 null 由调用方跳过。
 *
 * <p>失败语义：Python 不可用、超时、退出码非零或输出非法 JSON 时一律返回 null（跳过该脚本），不向上抛出，
 * 避免单个脚本问题阻断整表加载；仅当 Python 进程无法启动时向 stderr 提示一次。
 *
 * <p>运行期改动：解析结果按「脚本绝对路径 + 文件签名（修改时间:大小）」缓存，文件未改动时复用缓存；
 * 脚本被修改后签名变化，下次加载即重新执行 {@code --describe}，因此改动在下一次调用生效。
 */
public final class ScriptToolLoader {

    private static final long DESCRIBE_TIMEOUT_MILLIS = 15_000L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 脚本声明缓存：脚本绝对路径 → （文件签名, 声明）。 */
    private final Map<String, CachedTool> cache = new ConcurrentHashMap<String, CachedTool>();

    private volatile boolean pythonMissingWarned;

    /**
     * 加载单个脚本的工具声明。
     *
     * @param script 脚本文件，不可为 null
     * @return 工具方法定义；脚本非工具、执行失败或输出非法时返回 null
     */
    public ToolMethod load(File script) {
        if (script == null || !script.isFile()) {
            return null;
        }
        String path = script.getAbsolutePath();
        String stamp = stamp(script);
        CachedTool cached = cache.get(path);
        if (cached != null && cached.stamp.equals(stamp)) {
            return cached.method;
        }
        ToolMethod method = parse(describe(script), script);
        if (method != null) {
            cache.put(path, new CachedTool(stamp, method));
        }
        return method;
    }

    /**
     * 解析 {@code --describe} 输出为工具方法定义；无输出或输出非法时返回 null。
     */
    private ToolMethod parse(String stdout, File script) {
        if (stdout == null || stdout.trim().isEmpty()) {
            return null;
        }
        try {
            return toTool(MAPPER.readTree(stdout), script);
        } catch (IOException e) {
            return null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 文件签名（修改时间:大小），用于判断脚本是否改动并决定是否复用缓存。
     */
    private static String stamp(File file) {
        return file.lastModified() + ":" + file.length();
    }

    /**
     * 脚本声明缓存项：文件签名与解析结果。
     */
    private static final class CachedTool {

        private final String stamp;
        private final ToolMethod method;

        private CachedTool(String stamp, ToolMethod method) {
            this.stamp = stamp;
            this.method = method;
        }
    }

    /**
     * 运行 {@code python <script> --describe} 并返回 stdout 文本；失败返回 null。
     */
    private String describe(File script) {
        Process process = null;
        try {
            List<String> command = new ArrayList<String>();
            command.add(PythonScriptExecutor.pythonPath());
            command.add(script.getAbsolutePath());
            command.add("--describe");
            File workingDir = script.getAbsoluteFile().getParentFile();
            ProcessBuilder builder = new ProcessBuilder(command).directory(workingDir);
            builder.environment().put("PYTHONUTF8", "1");
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
            process = builder.start();
            String stdout = new String(readAll(process.getInputStream()), StandardCharsets.UTF_8);
            readAll(process.getErrorStream());
            if (!process.waitFor(DESCRIBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return process.exitValue() == 0 ? stdout : null;
        } catch (IOException e) {
            warnPythonMissing(script, e);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private void warnPythonMissing(File script, IOException cause) {
        if (pythonMissingWarned) {
            return;
        }
        pythonMissingWarned = true;
        System.err.println("[skill] 无法执行 Python 以加载脚本工具（" + script.getName() + "）："
                + cause.getMessage() + "；请确认已安装 Python 并加入 PATH（或设置 PYTHON_PATH）。");
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private ToolMethod toTool(JsonNode root, File script) {
        String id = text(root, "id");
        String name = text(root, "name");
        String description = text(root, "description");
        ScriptTypeEnum type = type(root);
        if (blank(id) || blank(name) || blank(description) || type == null) {
            return null;
        }
        return new ToolMethod(id, name, type, description, parameters(root), script.getPath(),
                metadata(root.get("metadata")));
    }

    private static ScriptTypeEnum type(JsonNode root) {
        String value = text(root, "type");
        if (value == null) {
            return ScriptTypeEnum.PYTHON;
        }
        for (ScriptTypeEnum candidate : ScriptTypeEnum.values()) {
            if (candidate.name().equalsIgnoreCase(value)) {
                return candidate;
            }
        }
        return null;
    }

    private static String parameters(JsonNode root) {
        JsonNode node = root.get("parameters");
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isTextual() ? node.textValue() : node.toString();
    }

    private static Map<String, String> metadata(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Map<String, String> result = new LinkedHashMap<String, String>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            result.put(field.getKey(), value == null || value.isNull() ? "" : value.asText());
        }
        return result;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
