package com.clara.adapter.console;

import com.clara.context.CancellationToken;
import com.clara.core.interaction.IUserInterventionSink;
import org.jline.terminal.Terminal;

import java.io.Closeable;
import java.io.IOException;

/**
 * 控制台执行期按键监听：ESC 经二次确认后置位本轮取消令牌，终止正在进行的模型调用与工具执行；
 * Tab 进入补充说明输入，提交内容由编排层在下一次模型请求前注入。
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
    /**
     * 终端是否由本类创建：自建终端由本类关闭；宿主传入的共享终端由宿主关闭，避免提前关闭后行编辑无法还原。
     */
    private final boolean ownsTerminal;

    private CancelKeyMonitor(ConsoleView view, Terminal terminal, boolean ownsTerminal) {
        this.view = view;
        this.terminal = terminal;
        this.ownsTerminal = ownsTerminal;
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
        return new CancelKeyMonitor(view, ConsoleTerminalFactory.open(), true);
    }

    /**
     * 使用宿主已打开的终端创建监听器：与行编辑共用同一个终端实例，避免两套原始模式互相覆盖。
     *
     * @param view     控制台视图，不可为 null
     * @param terminal 宿主打开的系统终端，可为 null（降级为不支持 ESC 取消）
     * @return 监听器，非 null
     */
    public static CancelKeyMonitor create(ConsoleView view, Terminal terminal) {
        if (view == null) {
            throw new IllegalArgumentException("view must not be null");
        }
        return new CancelKeyMonitor(view, terminal, false);
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
        return watch(cancellationToken, null);
    }

    /**
     * 开始一轮监听，并接入执行期用户补充通道。
     *
     * <p>执行中按 Tab 进入补充输入；提交内容写入 {@code interventionSink}，由编排层在下一次模型请求前消费。
     * 只有补充通道、没有取消令牌时同样可以监听（ESC 取消需要令牌）。
     *
     * @param cancellationToken 本轮取消令牌，可为 null
     * @param interventionSink  用户补充写入端，可为 null（不启用 Tab 补充）
     * @return 监听会话，非 null
     */
    public CancelKeySession watch(CancellationToken cancellationToken,
            IUserInterventionSink interventionSink) {
        CancelKeySession session = new CancelKeySession(view, terminal, cancellationToken, interventionSink);
        session.start();
        return session;
    }

    /**
     * 关闭监听器：还原并释放系统终端。
     */
    @Override
    public void close() {
        if (terminal == null || !ownsTerminal) {
            return;
        }
        try {
            terminal.close();
        } catch (IOException ignored) {
            // 关闭失败不影响进程退出
        }
    }

}
