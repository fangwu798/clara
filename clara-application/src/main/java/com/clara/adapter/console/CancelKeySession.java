package com.clara.adapter.console;

import com.clara.context.CancellationToken;
import com.clara.core.interaction.IUserInterventionSink;
import com.clara.enums.interaction.CancelConfirmationDecisionEnum;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;

import java.io.Closeable;
import java.io.IOException;

/**
 * 一轮执行的按键监听会话：ESC 双击取消本轮，Tab 进入补充说明输入；与一轮执行同生命周期，
 * 关闭后监听线程退出原始模式并还原控制台。
 */
public final class CancelKeySession implements Closeable {

    /**
     * 读取按键的轮询间隔（毫秒）：既用于及时响应监听停止，也用于检测确认窗口超时。
     */
    private static final long POLL_MILLIS = 100L;
    /**
     * 确认窗口时长（毫秒）：按下 ESC 后需在此期间再次确认，否则放弃取消。
     */
    private static final long CONFIRM_WINDOW_MILLIS = 5_000L;
    /**
     * 等待监听线程退出的时长（毫秒）。
     */
    private static final long STOP_JOIN_MILLIS = 500L;
    /** Tab 键值：执行中按下后进入补充说明输入模式。 */
    private static final int TAB = 9;
    /** 退格键值（DEL）。 */
    private static final int BACKSPACE = 127;
    /** 退格键值（BS，部分终端使用）。 */
    private static final int BACKSPACE_BS = 8;

    private final ConsoleView view;
    private final Terminal terminal;
    private final CancellationToken cancellationToken;
    private final IUserInterventionSink interventionSink;
    private final CancelConfirmation confirmation = new CancelConfirmation(CONFIRM_WINDOW_MILLIS);

    private volatile boolean running;
    private Thread worker;

    CancelKeySession(ConsoleView view, Terminal terminal, CancellationToken cancellationToken) {
        this(view, terminal, cancellationToken, null);
    }

    CancelKeySession(ConsoleView view, Terminal terminal, CancellationToken cancellationToken,
            IUserInterventionSink interventionSink) {
        this.view = view;
        this.terminal = terminal;
        this.cancellationToken = cancellationToken;
        this.interventionSink = interventionSink;
    }

    /**
     * 启动监听线程；终端不可用、且既无取消令牌也无补充通道时为空操作。
     */
    void start() {
        if (terminal == null || (cancellationToken == null && interventionSink == null)) {
            return;
        }
        running = true;
        worker = new Thread(this::listen, "clara-cancel-key");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 停止监听：置位停止标记并等待监听线程退出原始模式。
     */
    @Override
    public void close() {
        running = false;
        Thread current = worker;
        if (current == null) {
            return;
        }
        current.interrupt();
        try {
            current.join(STOP_JOIN_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 监听循环：原始模式下逐次读取按键，驱动确认状态机；确认后置位取消令牌并退出。
     */
    private void listen() {
        Attributes previous;
        try {
            previous = terminal.enterRawMode();
        } catch (RuntimeException unsupported) {
            return;
        }
        try {
            NonBlockingReader reader = terminal.reader();
            while (running) {
                int key = readKey(reader);
                if (!running || key == NonBlockingReader.EOF) {
                    break;
                }
                if (key == NonBlockingReader.READ_EXPIRED) {
                    if (confirmation.onTimeout(System.currentTimeMillis())) {
                        view.emit("[cancel] 未在时限内确认，继续执行。", ConsoleView.ANSI_GRAY);
                    }
                } else if (handleKey(key, reader, previous)) {
                    return;
                }
            }
        } catch (RuntimeException ignored) {
            // 读取失败时静默停止监听，不影响对话
        } finally {
            restore(previous);
        }
    }

    /**
     * 处理一次按键：Tab 进入补充说明输入，其余按键交给 ESC 取消确认状态机。
     *
     * @param key    键值
     * @param reader 终端按键读取器，非 null
     * @param cooked 进入原始模式前的控制台属性，用于补充输入时临时恢复行编辑
     * @return 是否应结束监听（用户已确认取消）
     */
    private boolean handleKey(int key, NonBlockingReader reader, Attributes cooked) {
        if (key == TAB && interventionSink != null && !confirmation.isAwaiting()) {
            readIntervention(reader, cooked);
            return false;
        }
        boolean awaiting = confirmation.isAwaiting();
        CancelConfirmationDecisionEnum decision = confirmation.onKey(key, System.currentTimeMillis());
        if (decision == CancelConfirmationDecisionEnum.PROMPT) {
            view.emit("[cancel] 已按下 ESC，再按一次 ESC（或回车 / y）确认取消当前执行；其他键忽略。",
                    ConsoleView.ANSI_RED);
            return false;
        }
        if (decision == CancelConfirmationDecisionEnum.CANCEL) {
            if (cancellationToken != null) {
                cancellationToken.cancel();
            }
            view.emit("[cancel] 已请求取消当前执行…", ConsoleView.ANSI_RED);
            return true;
        }
        if (awaiting) {
            view.emit("[cancel] 已忽略，继续执行。", ConsoleView.ANSI_GRAY);
        }
        return false;
    }

    /**
     * 读取一条补充说明：临时恢复控制台行编辑，让退格、中文输入法与方向键由终端处理。
     *
     * <p>提交后写入 {@link IUserInterventionSink}，供编排层在下一次模型请求前消费；空行或 Esc 取消。
     *
     * @param reader 终端按键读取器，非 null
     * @param cooked 进入原始模式前的控制台属性，可为 null
     */
    private void readIntervention(NonBlockingReader reader, Attributes cooked) {
        // 暂停底部进度行并暂存其它线程的输出，否则会覆盖用户正在输入的内容。
        view.beginInterventionInput("[intervene] 输入补充说明，Enter 提交，空行取消。");
        boolean restored = enterCookedMode(cooked);
        StringBuilder text = new StringBuilder();
        try {
            while (running) {
                int key = readKey(reader);
                if (!running || key == NonBlockingReader.EOF) {
                    return;
                }
                if (key == NonBlockingReader.READ_EXPIRED) {
                    continue;
                }
                if (key == '\r' || key == '\n') {
                    break;
                }
                if (key == BACKSPACE || key == BACKSPACE_BS) {
                    deleteLastCodePoint(text);
                    continue;
                }
                if (key == 27) {
                    text.setLength(0);
                    break;
                }
                if (key >= 32) {
                    text.appendCodePoint(key);
                }
            }
        } finally {
            if (restored) {
                try {
                    terminal.enterRawMode();
                } catch (RuntimeException ignored) {
                    // 恢复失败时监听循环会在下一轮读取异常后静默退出
                }
            }
            view.endInterventionInput();
        }
        String supplement = text.toString().trim();
        if (supplement.isEmpty()) {
            view.emit("[intervene] 已取消本次补充。", ConsoleView.ANSI_GRAY);
            return;
        }
        interventionSink.offer(supplement);
        view.emit("[intervene] 已记录补充，将在下一次模型请求前生效：" + supplement,
                ConsoleView.ANSI_YELLOW);
    }

    /**
     * 临时恢复进入原始模式前的属性；终端不支持时返回 false，继续按原始模式逐键读取。
     */
    private boolean enterCookedMode(Attributes cooked) {
        if (cooked == null) {
            return false;
        }
        try {
            terminal.setAttributes(cooked);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * 删除最后一个 Unicode 码点，避免退格把代理对拆成半个字符。
     */
    private static void deleteLastCodePoint(StringBuilder text) {
        if (text.length() == 0) {
            return;
        }
        int index = text.length() - 1;
        if (Character.isLowSurrogate(text.charAt(index)) && index > 0
                && Character.isHighSurrogate(text.charAt(index - 1))) {
            index--;
        }
        text.delete(index, text.length());
    }

    /**
     * 读取一个键值：正常超时返回 {@link NonBlockingReader#READ_EXPIRED}；读取异常视为终端不可用，返回
     * {@link NonBlockingReader#EOF} 让监听循环退出（避免异常路径上的忙轮询）。
     */
    private int readKey(NonBlockingReader reader) {
        try {
            return reader.read(POLL_MILLIS);
        } catch (IOException | RuntimeException failure) {
            return NonBlockingReader.EOF;
        }
    }

    /**
     * 还原进入原始模式前的控制台属性。
     */
    private void restore(Attributes previous) {
        try {
            terminal.setAttributes(previous);
        } catch (RuntimeException ignored) {
            // 还原失败时随进程退出回收
        }
    }
}
