package com.clara.adapter.console;

import com.clara.context.CancellationToken;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingReader;

import java.io.Closeable;

/**
 * 控制台单键 ESC 取消监听：在一轮对话执行期间，以原始模式监听 ESC，经二次确认后置位本轮取消令牌，从而终止
 * 正在进行的模型调用与工具执行。
 *
 * <p>是什么、在哪一层：application 边界适配层（{@code com.clara.adapter.console}）的输入监听器；它只负责
 * 「把按键变成对取消令牌的置位」，取消本身由内核的 {@link CancellationToken}（模型调用逐字节检查、脚本工具
 * 轮询检查）完成，故各层不必感知控制台。
 *
 * <p>为什么用 JLine：行模式下控制台要按回车才把整行交给应用，无法感知单键；JLine 提供原始模式单键读取，
 * 是「真单键 ESC」的前提。终端不可用（非交互环境、无原生提供者）时自动降级为不支持 ESC 取消。
 *
 * <p>谁创建谁调用：宿主在启动时 {@link #create(ConsoleView)} 一次；每轮执行前用
 * {@link #watch(CancellationToken)} 开一次监听会话，执行结束后关闭该会话。
 *
 * <p>线程与生命周期：监听在独立的守护线程上运行，只在监听会话期间进入原始模式；会话关闭时退出原始模式并还原
 * 控制台属性，因此监听线程与主线程对标准输入的读取在时间上互不重叠。
 *
 * <p>失败语义：终端创建或原始模式不可用时静默降级（{@link #isAvailable()} 为 {@code false}），不影响对话。
 */
public final class CancelKeyMonitor implements Closeable {

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

    /**
     * 控制台视图，用于输出提示与错误；非 null。
     */
    private final ConsoleView view;
    /**
     * JLine 系统终端；不可用时为 null（降级为不支持 ESC 取消）。
     */
    private final Terminal terminal;

    private CancelKeyMonitor(ConsoleView view, Terminal terminal) {
        this.view = view;
        this.terminal = terminal;
    }

    /**
     * 创建监听器：可用时持有系统终端，不可用时降级为「不支持 ESC 取消」。
     *
     * @param view 控制台视图，不可为 null
     * @return 监听器，非 null
     */
    public static CancelKeyMonitor create(ConsoleView view) {
        if (view == null) {
            throw new IllegalArgumentException("view must not be null");
        }
        Terminal terminal = null;
        try {
            Terminal candidate = TerminalBuilder.builder().system(true).build();
            String type = candidate.getType();
            if (type != null && !"dumb".equals(type)) {
                terminal = candidate;
            } else {
                candidate.close();
            }
        } catch (Throwable unavailable) {
            terminal = null;
        }
        return new CancelKeyMonitor(view, terminal);
    }

    /**
     * 当前环境是否支持单键 ESC 取消。
     *
     * @return 支持时返回 {@code true}
     */
    public boolean isAvailable() {
        return terminal != null;
    }

    /**
     * 开始一轮监听：返回的会话需在本次执行结束后关闭（建议配合 try-with-resources）。
     *
     * @param cancellationToken 本轮执行的取消令牌，可为 null（为 null 时返回空会话）
     * @return 监听会话，非 null
     */
    public Session watch(CancellationToken cancellationToken) {
        Session session = new Session(view, terminal, cancellationToken);
        session.start();
        return session;
    }

    /**
     * 关闭监听器：还原并释放系统终端。
     */
    @Override
    public void close() {
        if (terminal == null) {
            return;
        }
        try {
            terminal.close();
        } catch (Exception ignored) {
            // 关闭失败不影响进程退出
        }
    }

    /**
     * 一次监听会话：与一轮执行同生命周期；关闭后监听线程退出原始模式并还原控制台。
     */
    public static final class Session implements Closeable {

        private final ConsoleView view;
        private final Terminal terminal;
        private final CancellationToken cancellationToken;
        private final CancelConfirmation confirmation = new CancelConfirmation(CONFIRM_WINDOW_MILLIS);

        private volatile boolean running;
        private Thread worker;

        Session(ConsoleView view, Terminal terminal, CancellationToken cancellationToken) {
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
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    listen();
                }
            }, "clara-cancel-key");
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
            } catch (Exception unsupported) {
                return;
            }
            try {
                NonBlockingReader reader = terminal.reader();
                while (running) {
                    int key = readKey(reader);
                    if (!running) {
                        break;
                    }
                    if (key == NonBlockingReader.READ_EXPIRED) {
                        if (confirmation.onTimeout(System.currentTimeMillis())) {
                            view.emit("[cancel] 未在时限内确认，继续执行。", ConsoleView.ANSI_GRAY);
                        }
                        continue;
                    }
                    if (key == NonBlockingReader.EOF) {
                        break;
                    }
                    if (handleKey(key)) {
                        return;
                    }
                }
            } catch (Exception ignored) {
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
            CancelConfirmation.Decision decision = confirmation.onKey(key, System.currentTimeMillis());
            if (decision == CancelConfirmation.Decision.PROMPT) {
                view.emit("[cancel] 已按下 ESC，再按一次 ESC（或回车 / y）确认取消当前执行；其他键忽略。",
                        ConsoleView.ANSI_RED);
                return false;
            }
            if (decision == CancelConfirmation.Decision.CANCEL) {
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
            } catch (Exception failure) {
                return NonBlockingReader.EOF;
            }
        }

        /**
         * 还原进入原始模式前的控制台属性。
         */
        private void restore(Attributes previous) {
            try {
                terminal.setAttributes(previous);
            } catch (Exception ignored) {
                // 还原失败时随进程退出回收
            }
        }
    }
}
