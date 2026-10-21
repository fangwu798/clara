package com.clara.adapter.console;

import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.EndOfFileException;
import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.reader.Widget;
import org.jline.terminal.Terminal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

/**
 * 控制台输入：优先使用 JLine 行编辑，终端不可用时降级为按行读取。
 *
 * <p>按键约定：{@code Enter} 提交；{@code Shift+Enter} / {@code Ctrl+Enter} 在缓冲区插入换行（多行输入）；
 * 上 / 下方向键在历史输入中前后切换。历史既包含本次进程内提交过的输入，也包含启动时从持久化聊天记录
 * 里恢复的用户消息（见 {@link #seedHistory(List)}）。
 *
 * <p>为什么继承 {@link BufferedReader}：宿主与指令处理器已经按 {@code BufferedReader} 读取输入，
 * 继承后只需替换读取实现，模型选择、指令分发等既有调用方无需改动。
 *
 * <p>为什么带修饰键的回车需要特殊处理：原始模式下 {@code Enter} 发送回车符 {@code \r}；多数终端不会区分
 * {@code Shift+Enter} / {@code Ctrl+Enter}，Windows 控制台更是把它们都上报成回车。应用在 Windows 上使用
 * {@link org.jline.terminal.impl.jna.win.ClaraWinSysTerminal}，由终端层把带修饰键的回车改写为
 * {@link ConsoleKeySequences#CTRL_ENTER} / {@link ConsoleKeySequences#SHIFT_ENTER}；
 * 其他终端仍兼容 {@code \n}、CSI-u 以及 {@code Alt+Enter}。
 *
 * <p>生命周期：终端由宿主创建并在进程结束时关闭，本类不关闭终端，只关闭降级读取器。
 */
public final class ConsoleInput extends BufferedReader {

    /**
     * 交互提示符：浅灰色 › + 一个空格；仅在行编辑可用时显示。
     */
    private static final String PROMPT = "\033[37m›\033[0m ";

    /**
     * 自定义「插入换行」组件名。
     */
    private static final String NEWLINE_WIDGET = "clara-insert-newline";

    /**
     * Ctrl+Enter：支持该区分的终端发送 LF。
     */
    private static final String CTRL_ENTER = "\n";

    /**
     * Alt+Enter：终端无法区分 Ctrl+Enter 时的换行兜底（ESC + CR）。
     */
    private static final String ALT_ENTER = "\033\r";

    /**
     * Kitty 键盘协议以及 Windows 终端改写后的 Ctrl+Enter 序列。
     */
    private static final String CTRL_ENTER_CSI_U = ConsoleKeySequences.CTRL_ENTER;

    /**
     * Shift+Enter：Windows 终端改写与 Kitty 键盘协议使用的序列。
     */
    private static final String SHIFT_ENTER_CSI_U = ConsoleKeySequences.SHIFT_ENTER;

    /**
     * 回车符：单独按 Enter 提交。
     */
    private static final String ENTER = "\r";

    /**
     * 行编辑历史最多保留的条数。
     */
    private static final int HISTORY_SIZE = 500;

    /**
     * 单条历史输入最多保留的字符数，避免把超长粘贴内容整段放进历史。
     */
    private static final int MAX_HISTORY_ENTRY_CHARS = 4000;

    /**
     * 行编辑器；终端不可用时为 null（走父类的按行读取）。
     */
    private final ClaraLineReader lineReader;

    /**
     * 是否已经从持久化记录恢复过历史输入：{@code /init} 会重新走初始化流程，重复恢复会让历史成倍膨胀。
     */
    private boolean historySeeded;

    /**
     * 创建输入器。
     *
     * @param terminal 系统终端，可为 null
     * @param fallback 终端不可用时使用的按行读取器，不可为 null
     */
    ConsoleInput(Terminal terminal, Reader fallback) {
        super(fallback);
        this.lineReader = createLineReader(terminal);
    }

    /**
     * 创建控制台输入器。
     *
     * @param terminal 系统终端，可为 null（自动降级）
     * @return 输入器，非 null
     */
    public static ConsoleInput create(Terminal terminal) {
        return new ConsoleInput(terminal, new InputStreamReader(System.in, StandardCharsets.UTF_8));
    }

    /**
     * 当前环境是否支持方向键与多行编辑。
     *
     * @return 支持时返回 true
     */
    public boolean isLineEditingAvailable() {
        return lineReader != null;
    }

    /**
     * 设置输入行下方的状态行；状态每次重绘时实时计算，模型切换后自动生效。
     *
     * @param statusLine 状态行供应器，可为 null（不显示）
     */
    public void setStatusLine(Supplier<String> statusLine) {
        if (lineReader != null) {
            lineReader.setStatusLine(statusLine);
        }
    }

    /**
     * 读取一行输入。
     *
     * @return 用户输入（可含多行）；EOF 时返回 null；Ctrl+C 中断当前行时返回空串
     * @throws IOException 降级读取失败时
     */
    @Override
    public String readLine() throws IOException {
        if (lineReader == null) {
            return super.readLine();
        }
        try {
            return lineReader.readLine(PROMPT);
        } catch (EndOfFileException eof) {
            return null;
        } catch (UserInterruptException interrupted) {
            return "";
        }
    }

    /**
     * 追加一条可被上 / 下方向键召回的历史输入。
     *
     * @param input 已提交的输入，可为 null（忽略）
     */
    public void addHistory(String input) {
        if (lineReader == null || input == null) {
            return;
        }
        String line = input.trim();
        if (line.isEmpty()) {
            return;
        }
        if (line.length() > MAX_HISTORY_ENTRY_CHARS) {
            line = line.substring(0, MAX_HISTORY_ENTRY_CHARS);
        }
        History history = lineReader.getHistory();
        if (history.size() > 0 && line.equals(history.get(history.last()))) {
            return;
        }
        history.add(line);
    }

    /**
     * 批量恢复历史输入（按时间顺序），只保留最近 {@link #HISTORY_SIZE} 条。
     *
     * @param inputs 历史用户输入，可为 null（忽略）
     */
    public void seedHistory(List<String> inputs) {
        if (lineReader == null || historySeeded) {
            return;
        }
        historySeeded = true;
        if (inputs == null || inputs.isEmpty()) {
            return;
        }
        int from = Math.max(0, inputs.size() - HISTORY_SIZE);
        for (String input : inputs.subList(from, inputs.size())) {
            addHistory(input);
        }
    }

    /**
     * 关闭输入器。
     *
     * <p>行编辑模式下不关闭底层流：底层是终端持有的标准输入，提前关闭会让终端还原属性失败；
     * 进程退出时由操作系统回收。降级模式下按常规关闭。
     *
     * @throws IOException 降级读取器关闭失败时
     */
    @Override
    public void close() throws IOException {
        if (lineReader != null) {
            return;
        }
        super.close();
    }

    /**
     * 创建行编辑器并绑定换行键；终端不可用时返回 null。
     */
    private static ClaraLineReader createLineReader(Terminal terminal) {
        if (terminal == null) {
            return null;
        }
        try {
            ClaraLineReader reader = new ClaraLineReader(terminal, "clara");
            reader.setVariable(LineReader.HISTORY_SIZE, HISTORY_SIZE);
            // 续行只是普通换行，不再显示 JLine 默认的 "... " 续行提示符。
            reader.setVariable(LineReader.SECONDARY_PROMPT_PATTERN, "");
            bindKeys(reader);
            return reader;
        } catch (IOException | RuntimeException unsupported) {
            return null;
        }
    }

    /**
     * 绑定 Enter 提交、Shift+Enter / Ctrl+Enter（及 Alt+Enter 兜底）换行；方向键沿用 JLine 的历史检索绑定。
     */
    private static void bindKeys(LineReader reader) {
        Widget insertNewline = () -> {
            reader.getBuffer().write("\n");
            return true;
        };
        reader.getWidgets().put(NEWLINE_WIDGET, insertNewline);
        KeyMap<Binding> main = reader.getKeyMaps().get(LineReader.MAIN);
        if (main == null) {
            return;
        }
        main.bind(new Reference(NEWLINE_WIDGET), CTRL_ENTER, ALT_ENTER, CTRL_ENTER_CSI_U, SHIFT_ENTER_CSI_U);
        main.bind(new Reference(LineReader.ACCEPT_LINE), ENTER);
    }
}
