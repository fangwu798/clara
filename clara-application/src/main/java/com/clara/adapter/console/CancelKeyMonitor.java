package com.clara.adapter.console;

import com.clara.context.CancellationToken;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.Closeable;
import java.io.IOException;

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
        } catch (IOException | RuntimeException ignored) {
            // 终端不可用时静默降级为不支持 ESC 取消
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
    public CancelKeySession watch(CancellationToken cancellationToken) {
        CancelKeySession session = new CancelKeySession(view, terminal, cancellationToken);
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
        } catch (IOException ignored) {
            // 关闭失败不影响进程退出
        }
    }

}
