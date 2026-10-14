package com.clara.adapter.console;

import com.clara.session.ModelSession;

import java.io.BufferedReader;
import java.nio.file.Path;

/**
 * 会话内指令处理器：识别并执行交互式控制指令（退出、日志模式、颜色、切换模型）。
 *
 * <p>是什么、在哪一层：application 的边界适配层（{@code com.clara.adapter.console}），把「指令解析与分发」
 * 从业务主循环中剥离，主循环只根据返回值决定「退出 / 继续 / 当作对话输入」。
 *
 * <p>指令一览：{@code exit}/{@code quit}/{@code /exit} 结束会话；{@code /log}（或 {@code /verbose}）
 * 切换精简/全量日志；{@code /color} 开关颜色；{@code /model} 重新选择模型；{@code /compress}（或
 * {@code /compact}）压缩当前上下文；{@code /new} 开始新对话；{@code /usage}（或 {@code /tokens}）
 * 统计 token 用量；{@code /?}（或 {@code /help}）打印命令提示。其余输入一律视为对话内容。
 */
public final class SessionCommandHandler {

    /**
     * 指令处理结果。
     */
    public enum Outcome {
        /** 结束会话。 */
        EXIT,
        /** 指令已处理，跳过本轮对话。 */
        HANDLED,
        /** 非指令，按对话输入处理。 */
        INPUT
    }

    /**
     * 会话级上下文控制：把「压缩上下文 / 新建对话」这类需要访问装配层与当前会话标识的操作交给宿主实现，
     * 指令处理器只负责识别命令并回调。
     */
    public interface ConversationControls {

        /** 压缩当前上下文（历史归纳为摘要）。 */
        void compressContext();

        /** 开始一段新对话（切换到新的会话标识，历史上下文清空）。 */
        void newConversation();

        /** 显示 token 用量统计（全部历史记录估算 + 本次进程累计调用）。 */
        void showUsage();
    }

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

    private final ConsoleView view;
    private final BufferedReader reader;
    private final ModelSession session;
    private final ConversationControls controls;

    /**
     * 创建指令处理器。
     *
     * @param view     控制台视图（用于提示与选择），不可为 null
     * @param reader   标准输入读取器（用于 {@code /model} 交互选择），不可为 null
     * @param session  会话级模型状态，不可为 null
     * @param controls 会话级上下文控制（压缩 / 新建对话）；可为 null（此时这两条指令不生效）
     */
    public SessionCommandHandler(ConsoleView view, BufferedReader reader, ModelSession session, ConversationControls controls) {
        this.view = view;
        this.reader = reader;
        this.session = session;
        this.controls = controls;
    }

    /**
     * 处理一行输入。
     *
     * @param input 已去除首尾空白的输入，非空
     * @return 处理结果：退出 / 已处理 / 非指令（按对话输入处理）
     */
    public Outcome handle(String input) {
        if ("exit".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input) || "/exit".equals(input)) {
            view.println("bye.");
            return Outcome.EXIT;
        }
        if (LOG_MODE_COMMAND.equals(input) || LOG_MODE_ALIAS.equals(input)) {
            view.toggleLogMode();
            view.emit("日志模式：" + (view.isFullLogging() ? "全量（含 HTTP 请求/响应与工具调用过程，过程日志以灰色显示）" : "精简（仅助手回复与状态行）"), ConsoleView.ANSI_GRAY);
            return Outcome.HANDLED;
        }
        if (COLOR_COMMAND.equals(input)) {
            view.toggleColors();
            view.println("颜色：" + (view.isColorsEnabled() ? "已开启" : "已关闭"));
            return Outcome.HANDLED;
        }
        if (MODEL_COMMAND.equals(input)) {
            switchModel();
            return Outcome.HANDLED;
        }
        if (COMPRESS_COMMAND.equals(input) || COMPRESS_ALIAS.equals(input)) {
            if (controls == null) {
                view.println("当前运行方式不支持压缩上下文。");
            } else {
                controls.compressContext();
            }
            return Outcome.HANDLED;
        }
        if (NEW_CONVERSATION_COMMAND.equals(input)) {
            if (controls == null) {
                view.println("当前运行方式不支持新建对话。");
            } else {
                controls.newConversation();
            }
            return Outcome.HANDLED;
        }
        if (USAGE_COMMAND.equals(input) || USAGE_ALIAS.equals(input)) {
            if (controls == null) {
                view.println("当前运行方式不支持用量统计。");
            } else {
                controls.showUsage();
            }
            return Outcome.HANDLED;
        }
        if (HELP_COMMAND.equals(input) || HELP_ALIAS.equals(input)) {
            printHelp();
            return Outcome.HANDLED;
        }
        return Outcome.INPUT;
    }

    /**
     * 打印内置命令提示：一行一个命令，给出用法与作用。
     */
    private void printHelp() {
        view.println("内置命令（一行一个）：");
        view.println("  /?            显示本命令提示（别名 /help）");
        view.println("  /model        重新选择模型");
        view.println("  /compress     压缩当前上下文：把历史归纳为摘要（别名 /compact）");
        view.println("  /new          开始新对话：上下文清空，旧历史保留在磁盘");
        view.println("  /usage        统计 token 用量：全部历史记录估算 + 本次进程累计调用（别名 /tokens）");
        view.println("  /log          切换精简 / 全量日志（别名 /verbose）");
        view.println("  /color        开关彩色输出");
        view.println("  exit | quit   结束会话（也可用 /exit）");
    }

    private void switchModel() {
        if (!session.canSwitch()) {
            view.println("未发现可选模型列表（启动时以参数指定了配置文件）。");
            return;
        }
        Path next = view.chooseModel(session.candidates(), reader);
        session.switchTo(next);
        view.emit("已切换模型：" + view.describe(session.providerConfig()) + "   [" + session.configPath() + "]");
        view.emit("");
    }
}
