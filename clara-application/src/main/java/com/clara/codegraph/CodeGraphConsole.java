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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 代码知识图谱控制台入口：给 {@code /graph} 指令提供「查看状态」与「导入当前工作目录」两个动作，
 * 并在启动时按需把当前工作目录补挂进图谱容器。
 *
 * <p>是什么、在哪一层：application 的图谱运维入口（包 {@code com.clara.codegraph}），把动作转成对
 * {@code config/skill/scripts/code-graph/code_graph.py}（查看 / 导入）或
 * {@code deploy/codebase-memory-mcp/ensure-mount.ps1}（按需挂载）的一次调用。
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
     * 按需挂载脚本相对仓库根的路径（把当前工作目录补挂进图谱容器，见 {@link #ensureMount()}）。
     */
    private static final String MOUNT_SCRIPT = "deploy/codebase-memory-mcp/ensure-mount.ps1";

    /**
     * 按需挂载脚本每行输出的前缀，用来把它的说明挑出来。
     */
    private static final String MOUNT_PREFIX = "[cbm-mount] ";

    /**
     * 查看状态的超时：一次项目探测（容器里约 3 秒）足够，给足余量。
     */
    private static final long STATUS_TIMEOUT_SECONDS = 120L;

    /**
     * 导入的超时：大仓库几分钟，给到 30 分钟。
     */
    private static final long IMPORT_TIMEOUT_SECONDS = 1800L;

    /**
     * 按需挂载的超时：目录已挂上时只跑一次 docker inspect（秒回），要补挂时多一次容器重建，给足余量。
     */
    private static final long MOUNT_TIMEOUT_SECONDS = 300L;

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
     * 确保当前工作目录对图谱容器可见：不在容器的挂载里就补一条只读挂载（必要时重建容器）。
     *
     * <p>为什么在启动阶段做：容器不会因为换了工作目录就自动看到新目录 —— 缺挂载时图谱对该目录一律回
     * 「容器里看不到它」，读代码便会悄悄退回本地文件。补挂走「一条目录一条挂载、容器内路径由宿主路径
     * 派生」的口径（见 {@code deploy/codebase-memory-mcp/ensure-mount.ps1}），给新目录补挂不会挪动已有
     * 目录在容器里的位置，所以已建好的图谱不必重做索引。
     *
     * <p>只在 Windows 上做：按需挂载脚本是 PowerShell + Docker Desktop 的组合；其它平台、以及宿主机
     * 安装图谱（没有容器）时一律跳过，改由 {@code index-workdir.ps1} 手工处理。
     *
     * @return 可打印的中文结果，非 null
     */
    public String ensureMount() {
        if (!isWindows()) {
            return "[skip] 按需挂载只在 Windows + Docker Desktop 上可用，已跳过。";
        }
        Path script = ConfigLocator.locate(Paths.get(MOUNT_SCRIPT));
        if (script == null) {
            return "[warn] 未找到按需挂载脚本：" + MOUNT_SCRIPT + "（不用容器部署图谱时忽略即可）。";
        }
        // -Optional：宿主机上没跑容器部署时只提示、不报错（启动时不该每次弹一段失败信息）。
        ProcessBuilder builder = new ProcessBuilder(powershellCommand(), "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", script.toString(), "-WorkDir", ConfigLocator.workspaceRoot().toString(), "-Optional");
        return describeMount(run(builder, null, MOUNT_TIMEOUT_SECONDS, "挂载脚本", null));
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
        // 显式把「当前工作目录」传下去（等同启动脚本写的 CLARA_WORK_DIR）：脚本按它认定要导入 / 查询的
        // 目录，不传时它退回 Clara 仓库根——用 java -jar 直接启动就会把 Clara 仓库当成你的工作目录。
        builder.environment().put("CLARA_CBM_WORK_DIR", ConfigLocator.workspaceRoot().toString());
        Result result = run(builder, requestJson, timeoutSeconds, "图谱",
                "导入大仓库请改用 deploy\\codebase-memory-mcp\\index-workdir.ps1（不受此超时限制）。");
        return result.exitCode < 0 ? result.output : describe(result.output);
    }

    /**
     * 跑一次子进程并收齐结果（退出码 + 合并后的输出）。
     *
     * <p>为什么自己管 stdin / stdout / 超时：宿主侧单次技能脚本执行有 30 秒硬超时，而建索引是分钟级的，
     * 图谱调用必须自己控制生命周期。
     *
     * @param builder        已配好的进程构造器，不可为 null
     * @param stdinText      要写入子进程标准输入的内容；为 null 表示不写（直接关掉管道）
     * @param timeoutSeconds 等待上限（秒）
     * @param label          出错信息里的主语（「图谱」/「挂载脚本」）
     * @param timeoutHint    超时时附在后面的补充提示；可为 null
     * @return 调用结果，非 null；{@code exitCode < 0} 表示 output 已是可以直接打印的说明
     */
    private static Result run(ProcessBuilder builder, String stdinText, long timeoutSeconds, String label,
            String timeoutHint) {
        // 脚本把结果写 stdout、把诊断写 stderr；合并读取，出错时错误信息也不会丢。
        builder.redirectErrorStream(true);
        Process process;
        try {
            process = builder.start();
        } catch (IOException failure) {
            return new Result(-1, "[error] 启动" + label + "失败：" + failure.getMessage());
        }
        try {
            OutputStream stdin = process.getOutputStream();
            try {
                if (stdinText != null) {
                    stdin.write(stdinText.getBytes(StandardCharsets.UTF_8));
                    stdin.flush();
                }
            } finally {
                stdin.close();
            }
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                // 输出量很小（几 KB），不会把管道写满；超时说明确确实实还在建索引。
                process.destroyForcibly();
                process.waitFor();
                return new Result(-1, "[error] " + label + "调用超时（" + timeoutSeconds + " 秒）。"
                        + (timeoutHint == null ? "" : timeoutHint));
            }
            return new Result(process.exitValue(), readAll(process.getInputStream()));
        } catch (IOException failure) {
            return new Result(-1, "[error] 调用" + label + "失败：" + failure.getMessage());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new Result(-1, "[error] " + label + "调用被中断。");
        }
    }

    /**
     * 一次子进程调用的结果。
     */
    private static final class Result {

        /** 退出码；负值表示 output 已是可以直接打印的说明。 */
        private final int exitCode;

        /** 子进程输出（合并 stdout 与 stderr）。 */
        private final String output;

        private Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
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
     * 取要用的 PowerShell：优先启动脚本写入的 {@code CLARA_POWERSHELL}，其次 PATH 里的 {@code powershell}。
     *
     * @return 解释器命令，非 null
     */
    private static String powershellCommand() {
        String configured = System.getenv("CLARA_POWERSHELL");
        return configured == null || configured.trim().isEmpty() ? "powershell" : configured.trim();
    }

    /**
     * 当前是否 Windows：按需挂载脚本是 PowerShell + Docker Desktop 的组合，其它平台跳过。
     *
     * @return true 表示运行在 Windows 上
     */
    private static boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase(Locale.ROOT).startsWith("windows");
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
     * 把按需挂载脚本的输出渲染成几行中文结果（脚本每行以 {@code [cbm-mount] } 开头）。
     *
     * @param result 子进程调用结果，不可为 null
     * @return 可打印文本，非 null
     */
    private static String describeMount(Result result) {
        if (result.exitCode < 0) {
            return result.output;
        }
        List<String> notes = new ArrayList<>();
        String lastPlain = "";
        for (String line : result.output.split("\r?\n")) {
            String text = line.trim();
            if (text.isEmpty()) {
                continue;
            }
            if (text.startsWith(MOUNT_PREFIX)) {
                notes.add(text.substring(MOUNT_PREFIX.length()));
            } else {
                lastPlain = text;
            }
        }
        boolean skipped = false;
        for (String note : notes) {
            if (note.startsWith("跳过")) {
                skipped = true;
                break;
            }
        }
        // 已可见（启动时最常走的那条路）只有一句说明：直接回它，免得每次启动都多两行套话。
        if (result.exitCode == 0 && !skipped && notes.size() == 1) {
            return notes.get(0);
        }
        StringBuilder out = new StringBuilder();
        if (result.exitCode == 0) {
            out.append(skipped ? "结果：跳过按需挂载（见说明）\n" : "结果：容器能看到当前工作目录\n");
        } else if (result.exitCode == 3) {
            out.append("结果：容器正在建索引，本次先不补挂（下次启动会再试）\n");
        } else {
            out.append("结果：按需挂载未成功（退出码 ").append(result.exitCode).append("）\n");
        }
        if (!notes.isEmpty()) {
            out.append("说明：").append(String.join("；", notes)).append('\n');
        }
        if (result.exitCode != 0 && result.exitCode != 3) {
            String reason = lastPlain.isEmpty() && !notes.isEmpty() ? notes.get(notes.size() - 1) : lastPlain;
            if (!reason.isEmpty()) {
                out.append("原因：").append(reason).append('\n');
            }
        }
        return out.toString().trim();
    }

    /**
     * 把技能脚本返回的 JSON 渲染成几行中文结果（解析不了时原样返回）。
     *
     * <p>包内可见是为了单测：渲染规则（尤其 prepare 的几种动作）不需要真起进程就能验证。
     *
     * @param raw 脚本输出，可为 null
     * @return 可打印文本，非 null
     */
    static String describe(String raw) {
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
        String action = field(root, "action");
        StringBuilder out = new StringBuilder();
        if ("prepare".equals(op) && action != null) {
            // prepare 按动作说清楚：index_failed 这类结果里的 error 只是原因，整条渲染成「失败」会丢掉
            // 「未导入 + 后台导入失败」这层上下文
            out.append(prepareSummary(root)).append('\n');
            if (root.hasNonNull("error")) {
                out.append("原因：").append(field(root, "error")).append('\n');
            }
        } else if (root.hasNonNull("error")) {
            out.append("结果：失败\n原因：").append(field(root, "error")).append('\n');
        } else if ("prepare".equals(op)) {
            out.append(prepareSummary(root)).append('\n');
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

    /**
     * 把 prepare 的结果按「动作」说清楚：已导入 / 已在后台发起导入 / 发起失败 / 未导入。
     *
     * <p>为什么不只看 prepared：prepare(index=true) 发现图谱里没有这个项目时会顺手发起后台导入，
     * 这时 prepared 仍是 false，只按它渲染会打出「还没导入图谱」+「已在后台发起导入」这种自相矛盾的组合。
     *
     * @param root 脚本返回的 JSON 根节点，非 null
     * @return 中文结论，非 null
     */
    private static String prepareSummary(JsonNode root) {
        String action = field(root, "action");
        if ("reuse".equals(action)) {
            return "结果：已导入图谱，无需处理";
        }
        if ("indexing".equals(action)) {
            return "结果：未导入，已在后台发起导入（大仓库要几分钟，完成后用 /graph 查看）";
        }
        if ("index_failed".equals(action)) {
            return "结果：未导入，后台发起导入失败";
        }
        if ("needs_index".equals(action)) {
            return "结果：未导入（本轮读代码仍回退本地文件）";
        }
        return root.path("prepared").asBoolean(false) ? "结果：已导入图谱，无需处理" : "结果：未导入";
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
