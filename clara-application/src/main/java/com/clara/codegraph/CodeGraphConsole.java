package com.clara.codegraph;

import com.clara.config.ConfigLocator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * 代码知识图谱控制台入口：给 {@code /graph} 指令提供「查看状态」与「导入当前工作目录」两个动作。
 *
 * <p>是什么、在哪一层：application 的图谱运维入口（包 {@code com.clara.codegraph}），把动作转成对
 * {@code config/skill/scripts/code-graph/code_graph.py} 的一次调用。
 *
 * <p>为什么复用技能脚本：宿主 / 容器两种部署形态、挂载根与容器内路径的换算、项目名沿用与目录匹配、
 * 「图谱不可用」时的兜底文案，都已经在脚本里；Java 侧再实现一遍必然走偏。
 *
 * <p>为什么不走技能工具调用：宿主侧单次技能脚本执行有 30 秒硬超时，而建索引是分钟级的，所以这里直接起
 * 进程并自备超时（导入给 30 分钟）。
 */
public final class CodeGraphConsole {

    /**
     * 技能脚本相对仓库根的路径。
     */
    private static final String SCRIPT = "config/skill/scripts/code-graph/code_graph.py";

    /**
     * 查看状态的超时：一次项目探测（容器里约 3 秒）足够，给足余量。
     */
    private static final long STATUS_TIMEOUT_SECONDS = 120L;

    /**
     * 导入的超时：大仓库几分钟，给到 30 分钟。
     */
    private static final long IMPORT_TIMEOUT_SECONDS = 1800L;

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 查看当前工作目录是否已导入图谱（只查不建，秒回）。
     *
     * @return 可打印的中文结果，非 null
     */
    public String status() {
        return execute("{\"op\":\"prepare\",\"purpose\":\"查看当前工作目录是否已导入代码知识图谱\"}",
                STATUS_TIMEOUT_SECONDS);
    }

    /**
     * 幂等检查并按需导入：已导入时只回一句「已就绪」，没导入时才（可选）发起导入。
     *
     * @param index true 表示没导入时顺带发起导入（后台），false 表示只查不建
     * @return 可打印的中文结果，非 null
     */
    public String prepare(boolean index) {
        return execute("{\"op\":\"prepare\",\"index\":" + index
                + ",\"purpose\":\"检查当前工作目录是否已导入代码知识图谱，未导入则按需导入\"}",
                STATUS_TIMEOUT_SECONDS);
    }

    /**
     * 把当前工作目录导入图谱。
     *
     * @param background true 表示后台发起（立刻返回，稍后用 {@code /graph} 查看结果）；
     *                   false 表示同步等待索引跑完（可能几分钟）
     * @return 可打印的中文结果，非 null
     */
    public String importWorkdir(boolean background) {
        return execute("{\"op\":\"index\",\"background\":" + background
                + ",\"purpose\":\"把当前工作目录导入代码知识图谱\"}",
                background ? STATUS_TIMEOUT_SECONDS : IMPORT_TIMEOUT_SECONDS);
    }

    private static String execute(String requestJson, long timeoutSeconds) {
        Path script = ConfigLocator.locate(Paths.get(SCRIPT));
        if (script == null) {
            return "[error] 未找到图谱技能脚本：" + SCRIPT + "（请在仓库根运行，或检查 config/ 目录）。";
        }
        ProcessBuilder builder = new ProcessBuilder(pythonCommand(), script.toString());
        // 脚本把结果写 stdout、把诊断写 stderr；合并读取，出错时错误信息也不会丢。
        builder.redirectErrorStream(true);
        Process process;
        try {
            process = builder.start();
        } catch (IOException failure) {
            return "[error] 启动图谱脚本失败：" + failure.getMessage();
        }
        try {
            OutputStream stdin = process.getOutputStream();
            try {
                stdin.write(requestJson.getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            } finally {
                stdin.close();
            }
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                // 输出量很小（几 KB），不会把管道写满；超时说明确确实实还在建索引。
                process.destroyForcibly();
                process.waitFor();
                return "[error] 图谱调用超时（" + timeoutSeconds + " 秒）。导入大仓库请改用 "
                        + "deploy\\codebase-memory-mcp\\index-workdir.ps1（不受此超时限制）。";
            }
            return describe(readAll(process.getInputStream()));
        } catch (IOException failure) {
            return "[error] 调用图谱失败：" + failure.getMessage();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return "[error] 图谱调用被中断。";
        }
    }

    /**
     * 取要用的 Python 解释器：优先启动脚本写入的 {@code PYTHON_PATH}（clara.bat / clara.sh 会写），
     * 其次 {@code PYTHON}，最后退回 PATH 里的 {@code python}。
     *
     * @return 解释器命令，非 null
     */
    private static String pythonCommand() {
        String configured = System.getenv("PYTHON_PATH");
        if (configured == null || configured.trim().isEmpty()) {
            configured = System.getenv("PYTHON");
        }
        return configured == null || configured.trim().isEmpty() ? "python" : configured.trim();
    }

    /**
     * 读出子进程的全部输出（Java 8 没有 InputStream#readAllBytes）。
     *
     * @param stream 子进程输出流，不可为 null
     * @return UTF-8 文本，非 null
     * @throws IOException 读取失败时
     */
    private static String readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = stream.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * 把技能脚本返回的 JSON 渲染成几行中文结果（解析不了时原样返回）。
     *
     * @param raw 脚本输出，可为 null
     * @return 可打印文本，非 null
     */
    private static String describe(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return "[warn] 图谱没有返回任何内容（可能没装 Python，或图谱二进制不可用）。";
        }
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (IOException failure) {
            return text;
        }
        String op = field(root, "op");
        StringBuilder out = new StringBuilder();
        if (root.hasNonNull("error")) {
            out.append("结果：失败\n原因：").append(field(root, "error")).append('\n');
        } else if ("prepare".equals(op)) {
            out.append(root.path("prepared").asBoolean(false)
                    ? "结果：当前工作目录已导入图谱\n" : "结果：当前工作目录还没导入图谱\n");
        } else {
            out.append(root.path("ok").asBoolean(false) ? "结果：已发起导入\n" : "结果：导入失败\n");
        }
        appendIfPresent(out, "项目", field(root, "project"));
        String host = field(root, "host_repo_path");
        appendIfPresent(out, "目录", host == null ? field(root, "repo_path") : host);
        appendIfPresent(out, "说明", field(root, "note"));
        appendIfPresent(out, "提示", field(root, "hint"));
        return out.toString().trim();
    }

    private static void appendIfPresent(StringBuilder text, String label, String value) {
        if (value != null && !value.trim().isEmpty()) {
            text.append(label).append("：").append(value.trim()).append('\n');
        }
    }

    private static String field(JsonNode root, String name) {
        JsonNode value = root.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }
}
