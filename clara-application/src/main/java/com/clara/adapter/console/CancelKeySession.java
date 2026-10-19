package com.clara.adapter.console;

import com.clara.context.CancellationToken;
import com.clara.enums.interaction.CancelConfirmationDecisionEnum;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;

import java.io.Closeable;
import java.io.IOException;

/**
 * 一次 ESC 监听会话：与一轮执行同生命周期；关闭后监听线程退出原始模式并还原控制台。
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

    private final ConsoleView view;
    private final Terminal terminal;
    private final CancellationToken cancellationToken;
    private final CancelConfirmation confirmation = new CancelConfirmation(CONFIRM_WINDOW_MILLIS);

    private volatile boolean running;
    private Thread worker;

    CancelKeySession(ConsoleView view, Terminal terminal, CancellationToken cancellationToken) {
        this.view = view;
        this.terminal = terminal;
        this.cancellationToken = cancellationToken;
    }

    /**
     * 启动监听线程；终端或令牌不可用时为空操作。
     */
    void start() {
        if (terminal == null || cancellationToken == null) {
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
                } else if (handleKey(key)) {
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
     * 处理一次按键。
     *
     * @param key 键值
     * @return 是否应结束监听（用户已确认取消）
     */
    private boolean handleKey(int key) {
        boolean awaiting = confirmation.isAwaiting();
        CancelConfirmationDecisionEnum decision = confirmation.onKey(key, System.currentTimeMillis());
        if (decision == CancelConfirmationDecisionEnum.PROMPT) {
            view.emit("[cancel] 已按下 ESC，再按一次 ESC（或回车 / y）确认取消当前执行；其他键忽略。",
                    ConsoleView.ANSI_RED);
            return false;
        }
        if (decision == CancelConfirmationDecisionEnum.CANCEL) {
            cancellationToken.cancel();
            view.emit("[cancel] 已请求取消当前执行…", ConsoleView.ANSI_RED);
            return true;
        }
        if (awaiting) {
            view.emit("[cancel] 已忽略，继续执行。", ConsoleView.ANSI_GRAY);
        }
        return false;
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
