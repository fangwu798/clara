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
 * 切换精简/全量日志；{@code /color} 开关颜色；{@code /model} 重新选择模型。其余输入一律视为对话内容。
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

    private static final String LOG_MODE_COMMAND = "/log";
    private static final String LOG_MODE_ALIAS = "/verbose";
    private static final String COLOR_COMMAND = "/color";
    private static final String MODEL_COMMAND = "/model";

    private final ConsoleView view;
    private final BufferedReader reader;
    private final ModelSession session;

    /**
     * 创建指令处理器。
     *
     * @param view    控制台视图（用于提示与选择），不可为 null
     * @param reader  标准输入读取器（用于 {@code /model} 交互选择），不可为 null
     * @param session 会话级模型状态，不可为 null
     */
    public SessionCommandHandler(ConsoleView view, BufferedReader reader, ModelSession session) {
        this.view = view;
        this.reader = reader;
        this.session = session;
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
        return Outcome.INPUT;
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
