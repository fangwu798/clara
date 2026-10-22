package com.clara.adapter.console;

import com.clara.config.ModelRegistry;
import com.clara.core.interaction.IConversationControls;
import com.clara.enums.interaction.HistoryClearScopeEnum;
import com.clara.enums.interaction.SessionCommandOutcomeEnum;

import java.io.BufferedReader;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 会话内指令处理器：识别并执行交互式控制指令（退出、日志模式、颜色、切换模型）。
 *
 * <p>是什么、在哪一层：application 的边界适配层（{@code com.clara.adapter.console}），把「指令解析与分发」
 * 从业务主循环中剥离，主循环只根据返回值决定「退出 / 继续 / 当作对话输入」。
 *
 * <p>指令一览：{@code exit}/{@code quit}/{@code /exit} 结束会话；{@code /log}（或 {@code /verbose}）
 * 切换精简/全量日志；{@code /color} 开关颜色；{@code /model} 重新选择模型；{@code /compress}（或
 * {@code /compact}）压缩当前上下文；{@code /new} 开始新对话；{@code /usage}（或 {@code /tokens}）
 * 统计 token 用量；{@code /init} 重新初始化（重新检测模型可用性并重建装配，保留当前会话）；
 * {@code /history clear} 清理全部历史、{@code /history clear -n xx} 清理最早的 xx 条、
 * {@code /history clear -t xx} 清理最新的 xx 条；{@code /?}（或 {@code /help}）打印命令提示。
 * 其余输入一律视为对话内容。
 */
public final class SessionCommandHandler {

    private static final String LOG_MODE_COMMAND = "/log";
    private static final String LOG_MODE_ALIAS = "/verbose";
    private static final String COLOR_COMMAND = "/color";
    private static final String MODEL_COMMAND = "/model";
    private static final String COMPRESS_COMMAND = "/compress";
    private static final String COMPRESS_ALIAS = "/compact";
    private static final String NEW_CONVERSATION_COMMAND = "/new";
    private static final String HELP_COMMAND = "/?";
    private static final String HELP_ALIAS = "/help";
    private static final String USAGE_COMMAND = "/usage";
    private static final String USAGE_ALIAS = "/tokens";
    private static final String HISTORY_COMMAND = "/history";
    private static final String GRAPH_COMMAND = "/graph";
    private static final String GRAPH_IMPORT_COMMAND = "/graph import";
    private static final String TRACE_COMMAND = "/trace";
    private static final String INIT_COMMAND = "/init";

    private final ConsoleView view;
    private final BufferedReader reader;
    private final ModelRegistry registry;
    private final List<Path> candidates;
    private final IConversationControls controls;
    private final Map<String, Runnable> commands = new HashMap<>();

    /**
     * 创建指令处理器。
     *
     * @param view       控制台视图（用于提示与选择），不可为 null
     * @param reader     标准输入读取器（用于 {@code /model} 交互选择），不可为 null
     * @param registry   模型注册表（解析别名、切换会话当前模型），不可为 null
     * @param candidates 可选模型配置清单（已按可用性过滤），可为 null（此时 {@code /model} 不可用）
     * @param controls   会话级上下文控制（压缩 / 新建对话 / 用量 / 代码图谱）；可为 null（此时相关指令只提示不支持）
     */
    public SessionCommandHandler(ConsoleView view, BufferedReader reader, ModelRegistry registry, List<Path> candidates, IConversationControls controls) {
        this.view = view;
        this.reader = reader;
        this.registry = registry;
        this.candidates = candidates;
        this.controls = controls;
        registerCommands();
    }

    /**
     * 处理一行输入。
     *
     * @param input 已去除首尾空白的输入，非空
     * @return 处理结果：退出 / 已处理 / 非指令（按对话输入处理）
     */
    public SessionCommandOutcomeEnum handle(String input) {
        if (isExitCommand(input)) {
            view.println("bye.");
            return SessionCommandOutcomeEnum.EXIT;
        }
        if (INIT_COMMAND.equals(input)) {
            return SessionCommandOutcomeEnum.REINIT;
        }
        if (TRACE_COMMAND.equals(input) || input.startsWith(TRACE_COMMAND + " ")) {
            String selector = input.length() <= TRACE_COMMAND.length()
                    ? null : input.substring(TRACE_COMMAND.length()).trim();
            runWithControls("当前运行方式不支持查看执行链路。",
                    controls -> controls.showTrace(selector));
            return SessionCommandOutcomeEnum.HANDLED;
        }
        if (HISTORY_COMMAND.equals(input) || input.startsWith(HISTORY_COMMAND + " ")) {
            handleHistory(input.substring(HISTORY_COMMAND.length()).trim());
            return SessionCommandOutcomeEnum.HANDLED;
        }
        Runnable command = commands.get(input);
        if (command == null) {
            return SessionCommandOutcomeEnum.INPUT;
        }
        command.run();
        return SessionCommandOutcomeEnum.HANDLED;
    }

    /**
     * 判断是否为退出命令（{@code /exit} 区分大小写，{@code exit}/{@code quit} 不区分）。
     */
    private static boolean isExitCommand(String input) {
        return "exit".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input) || "/exit".equals(input);
    }

    /**
     * 注册命令与处理动作：别名指向同一个动作，新增命令只需追加一行。
     */
    private void registerCommands() {
        Runnable logMode = this::toggleLogMode;
        commands.put(LOG_MODE_COMMAND, logMode);
        commands.put(LOG_MODE_ALIAS, logMode);

        commands.put(COLOR_COMMAND, this::toggleColors);
        commands.put(MODEL_COMMAND, this::switchModel);

        Runnable compress = () -> runWithControls("当前运行方式不支持压缩上下文。", IConversationControls::compressContext);
        commands.put(COMPRESS_COMMAND, compress);
        commands.put(COMPRESS_ALIAS, compress);

        commands.put(NEW_CONVERSATION_COMMAND, () -> runWithControls("当前运行方式不支持新建对话。", IConversationControls::newConversation));

        Runnable usage = () -> runWithControls("当前运行方式不支持用量统计。", IConversationControls::showUsage);
        commands.put(USAGE_COMMAND, usage);
        commands.put(USAGE_ALIAS, usage);

        commands.put(GRAPH_COMMAND, () -> runWithControls("当前运行方式不支持查看代码知识图谱。", IConversationControls::showCodeGraph));
        commands.put(GRAPH_IMPORT_COMMAND, () -> runWithControls("当前运行方式不支持导入代码知识图谱。", IConversationControls::importCodeGraph));

        commands.put(TRACE_COMMAND, () -> runWithControls("当前运行方式不支持查看执行链路。",
                control -> control.showTrace(null)));

        Runnable help = this::printHelp;
        commands.put(HELP_COMMAND, help);
        commands.put(HELP_ALIAS, help);
    }

    /**
     * 在会话级上下文控制可用时执行动作，否则输出不支持提示。
     */
    private void runWithControls(String unsupportedMessage, Consumer<IConversationControls> action) {
        if (controls == null) {
            view.println(unsupportedMessage);
            return;
        }
        action.accept(controls);
    }

    /**
     * 处理 {@code /history} 及其清理子命令。
     *
     * @param arguments {@code /history} 之后的参数，已去除首尾空白，非 null
     */
    private void handleHistory(String arguments) {
        if (arguments.isEmpty()) {
            runWithControls("当前运行方式不支持查看历史消息。", IConversationControls::showHistory);
            return;
        }
        String[] parts = arguments.split("\\s+");
        if (!"clear".equals(parts[0])) {
            printHistoryUsage("未知的历史指令：" + parts[0]);
            return;
        }
        if (parts.length == 1) {
            runWithControls("当前运行方式不支持清理历史消息。",
                    controls -> controls.clearHistory(HistoryClearScopeEnum.ALL, 0));
            return;
        }
        if (parts.length == 3 && isScopeOption(parts[1])) {
            Integer count = positiveCount(parts[2]);
            if (count == null) {
                printHistoryUsage("条数必须是大于 0 的整数。");
                return;
            }
            HistoryClearScopeEnum scope = "-n".equals(parts[1])
                    ? HistoryClearScopeEnum.FIRST : HistoryClearScopeEnum.LAST;
            runWithControls("当前运行方式不支持清理历史消息。",
                    controls -> controls.clearHistory(scope, count));
            return;
        }
        printHistoryUsage(null);
    }

    /**
     * 是否为支持的清理范围参数。
     */
    private static boolean isScopeOption(String option) {
        return "-n".equals(option) || "-t".equals(option);
    }

    /**
     * 解析正整数条数；非法时返回 null。
     */
    private static Integer positiveCount(String text) {
        try {
            int value = Integer.parseInt(text);
            return value > 0 ? Integer.valueOf(value) : null;
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    /**
     * 打印历史清理用法；{@code reason} 非空时先打印失败原因。
     */
    private void printHistoryUsage(String reason) {
        if (reason != null && !reason.isEmpty()) {
            view.println(reason);
        }
        view.println("用法：/history clear [-n <条数> | -t <条数>]");
        view.println("  /history clear       清空全部历史记录");
        view.println("  /history clear -n N  清理最早的 N 条历史记录");
        view.println("  /history clear -t N  清理最新的 N 条历史记录");
    }

    /**
     * 切换日志模式并提示当前模式。
     */
    private void toggleLogMode() {
        view.toggleLogMode();
        view.emit("日志模式：" + (view.isFullLogging()
                ? "全量（含 HTTP 请求/响应与工具调用过程，过程日志以灰色显示）"
                : "精简（仅助手回复与状态行）"), ConsoleView.ANSI_GRAY);
    }

    /**
     * 开关彩色输出并提示当前状态。
     */
    private void toggleColors() {
        view.toggleColors();
        view.println("颜色：" + (view.isColorsEnabled() ? "已开启" : "已关闭"));
    }

    /**
     * 打印内置命令提示：一行一个命令，给出用法与作用。
     */
    private void printHelp() {
        view.println("内置命令（一行一个）：");
        view.println("  /?            显示本命令提示（别名 /help）");
        view.println("  /model        重新选择模型");
        view.println("  /init         重新初始化：重新检测模型可用性并重建配置 / 技能 / MCP 连接（保留当前会话）");
        view.println("  /compress     压缩当前上下文：把历史归纳为摘要（别名 /compact）");
        view.println("  /new          开始新对话：上下文清空，旧历史保留在磁盘");
        view.println("  /usage        统计 token 用量：全部历史记录估算 + 本次进程累计调用（别名 /tokens）");
        view.println("  /history      查看历史聊天记录（仅用户与助手消息，助手消息带 token 用量）");
        view.println("  /history clear      清空全部历史记录");
        view.println("  /history clear -n N 清理最早的 N 条历史记录");
        view.println("  /history clear -t N 清理最新的 N 条历史记录");
        view.println("  /graph        查看当前工作目录的代码知识图谱状态（是否已导入 / 导入方式 / 可视化地址）");
        view.println("  /graph import 把当前工作目录导入代码知识图谱（同步等待索引完成，大仓库要几分钟）");
        view.println("  /trace        查看最近一轮执行链路（阶段 / 耗时 / token / 失败原因）");
        view.println("  /trace list   列出最近几条链路；/trace <traceId> 查看指定链路");
        view.println("  /log          切换精简 / 全量日志（别名 /verbose）");
        view.println("  /color        开关彩色输出");
        view.println("  exit | quit   结束会话（也可用 /exit）");
    }

    private void switchModel() {
        if (candidates == null || candidates.isEmpty()) {
            view.println("未发现可选模型列表（启动时以参数指定了配置文件）。");
            return;
        }
        Path next = view.chooseModel(candidates, reader);
        registry.select(next);
        view.emit("已切换模型：" + view.describe(registry.currentConfiguration()) + "   [" + registry.currentConfigPath() + "]");
        view.emit("");
    }
}
