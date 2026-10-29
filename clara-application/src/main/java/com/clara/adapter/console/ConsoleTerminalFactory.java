package com.clara.adapter.console;

import com.clara.core.diagnostics.Diagnostics;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.impl.jna.JnaTerminalProvider;
import org.jline.terminal.impl.jna.win.ClaraWinSysTerminal;
import org.jline.terminal.spi.SystemStream;
import org.jline.utils.OSUtils;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 控制台终端工厂：进程内只创建一个系统终端，供行编辑（{@link ConsoleInput}）与 ESC 取消监听
 * （{@link CancelKeyMonitor}）共用。
 *
 * <p>为什么必须共用：JLine 的终端会持有标准输入并切换原始模式。两个终端实例同时包装同一个标准输入时，
 * 谁先恢复属性谁就把对方刚设置的模式覆盖掉，表现为方向键失效或 ESC 监听失灵。因此由宿主启动时打开一次，
 * 再显式传给两个使用方。
 *
 * <p>失败语义：非交互环境（重定向输入、无原生提供者、dumb 终端）返回 {@code null}，调用方自动降级为
 * 普通的按行读取，不影响程序运行。
 */
public final class ConsoleTerminalFactory {

    /**
     * 终端类型为 {@code dumb} 时无法做行编辑与单键监听，视同不可用。
     */
    private static final String DUMB_TERMINAL = "dumb";

    private ConsoleTerminalFactory() {
    }

    /**
     * 打开系统终端。
     *
     * @return 可交互的系统终端；不可用时返回 {@code null}
     */
    public static Terminal open() {
        Terminal terminal = openWindowsTerminal();
        if (terminal != null) {
            return bind(terminal);
        }
        terminal = null;
        try {
            terminal = TerminalBuilder.builder().system(true).build();
            return bind(terminalIfUsable(terminal));
        } catch (IOException | RuntimeException ignored) {
            // 终端不可用时静默降级，由调用方使用普通行输入
        }
        closeQuietly(terminal);
        return null;
    }

    /**
     * 把可用终端登记给异常兜底：输入泵线程挂掉时由它恢复控制台并退出。
     *
     * @param terminal 可用终端，可为 null
     * @return 原样返回入参，便于链式调用
     */
    private static Terminal bind(Terminal terminal) {
        if (terminal != null) {
            TerminalFailureGuard.bind(terminal);
        }
        return terminal;
    }

    /**
     * 创建支持区分 Ctrl+Enter 的 Windows 终端。
     *
     * <p>为什么不走 {@link TerminalBuilder}：JLine 的提供者列表是写死的，无法注入自定义终端类型；
     * 这里直接复用 JLine 的 JNA 创建参数，失败时返回 {@code null} 由调用方回退到默认终端。
     *
     * @return 可用的终端；非 Windows、非控制台或创建失败时返回 {@code null}
     */
    private static Terminal openWindowsTerminal() {
        if (!OSUtils.IS_WINDOWS || !ClaraWinSysTerminal.isWindowsSystemStream(SystemStream.Output)) {
            return null;
        }
        try {
            Terminal terminal = ClaraWinSysTerminal.create(new JnaTerminalProvider(), SystemStream.Output,
                    "clara", resolveTerminalType(), false, resolveEncoding(), false, null, false);
            return terminalIfUsable(terminal);
        } catch (IOException | RuntimeException | LinkageError unsupported) {
            // 这里是「已确认是 Windows 控制台但创建失败」的分支：静默回退会让 Shift+Enter 换行能力悄悄失效，
            // 以后遇到同类问题很难发现，因此至少留一条告警。
            Diagnostics.warn("[WARN] 自定义终端创建失败，已回退默认终端（Shift+Enter 换行不可用）：" + unsupported);
            return null;
        }
    }

    /**
     * 过滤掉 dumb 终端：这类终端不支持行编辑与单键监听。
     *
     * @param terminal 已创建的终端
     * @return 可用终端；dumb 终端关闭后返回 {@code null}
     */
    private static Terminal terminalIfUsable(Terminal terminal) {
        String type = terminal.getType();
        if (type != null && !DUMB_TERMINAL.equals(type)) {
            return terminal;
        }
        closeQuietly(terminal);
        return null;
    }

    /**
     * 读取终端类型：与 JLine 一致，优先系统属性，其次 TERM 环境变量。
     *
     * @return 终端类型；未设置时返回 {@code null}
     */
    private static String resolveTerminalType() {
        String type = System.getProperty(TerminalBuilder.PROP_TYPE);
        return type != null ? type : System.getenv("TERM");
    }

    /**
     * 读取终端字符集：与 JLine 默认行为一致，使用 UTF-8。
     *
     * @return 字符集，非 null
     */
    private static Charset resolveEncoding() {
        String name = System.getProperty(TerminalBuilder.PROP_ENCODING);
        try {
            if (name != null && Charset.isSupported(name)) {
                return Charset.forName(name);
            }
        } catch (IllegalArgumentException ignored) {
            // 非法字符集名称按默认 UTF-8 处理
        }
        return StandardCharsets.UTF_8;
    }

    /**
     * 关闭终端，忽略关闭失败（进程即将退出或已降级）。
     *
     * @param terminal 终端，可为 null
     */
    private static void closeQuietly(Terminal terminal) {
        if (terminal == null) {
            return;
        }
        try {
            terminal.close();
        } catch (IOException ignored) {
            // 关闭失败不影响进程
        }
    }
}
