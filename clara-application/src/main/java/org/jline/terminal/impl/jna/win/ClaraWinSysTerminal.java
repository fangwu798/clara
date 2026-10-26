package org.jline.terminal.impl.jna.win;

import com.clara.adapter.console.ConsoleKeySequences;
import com.sun.jna.LastErrorException;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import org.jline.terminal.Terminal;
import org.jline.terminal.spi.SystemStream;
import org.jline.terminal.spi.TerminalProvider;
import org.jline.utils.OSUtils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;

/**
 * Windows 系统终端：在 JLine 默认实现上补齐 Shift+Enter / Ctrl+Enter 的区分能力。
 *
 * <p>Windows 控制台把 Enter 与 Ctrl+Enter 都上报为回车字符，JLine 会再次写回回车，行编辑器只能看到提交。
 * 本类覆写 getEscapeSequence，把 Shift+Enter / Ctrl+Enter 改写为 ConsoleKeySequences 中定义的序列，
 * 由行编辑器绑定为换行；单独 Enter 保持提交语义。
 *
 * <p>放在 JLine 包下是因为其 Windows 终端构造器包级可见，只有同包类型才能复用原生控制台句柄与写回逻辑。
 */
public final class ClaraWinSysTerminal extends JnaWinSysTerminal {

    /**
     * 标准输入控制台句柄。
     */
    private static final Pointer CONSOLE_IN = Kernel32.INSTANCE.GetStdHandle(Kernel32.STD_INPUT_HANDLE);

    /**
     * 标准输出控制台句柄。
     */
    private static final Pointer CONSOLE_OUT = Kernel32.INSTANCE.GetStdHandle(Kernel32.STD_OUTPUT_HANDLE);

    /**
     * 标准错误控制台句柄。
     */
    private static final Pointer CONSOLE_ERR = Kernel32.INSTANCE.GetStdHandle(Kernel32.STD_ERROR_HANDLE);

    private ClaraWinSysTerminal(TerminalProvider provider, SystemStream systemStream, Writer writer, String name,
            String type, Charset encoding, boolean nativeSignals, Terminal.SignalHandler signalHandler,
            Pointer inConsole, int inConsoleMode, Pointer outConsole, int outConsoleMode) throws IOException {
        super(provider, systemStream, writer, name, type, encoding, nativeSignals, signalHandler,
                inConsole, inConsoleMode, outConsole, outConsoleMode);
    }

    /**
     * 创建 Windows 系统终端：输出编码、VTP 探测、ConEmu 兼容与 JLine 默认实现保持一致。
     *
     * <p>JLine 的终端构造器要求信号处理器非空（内部会把它逐个登记到信号表，而 ConcurrentHashMap 不允许
     * null 值），宿主不注册原生信号时传 null 会抛 NPE，导致自定义终端创建失败并静默退回默认终端，
     * Shift+Enter 的区分能力随之丢失。这里统一把 null 兜底为 {@code SIG_DFL}。
     *
     * @param provider        终端提供者
     * @param systemStream    终端对应的系统流
     * @param name            终端名称
     * @param type            终端类型；为 null 时自动探测
     * @param ansiPassThrough 是否直通 ANSI 序列
     * @param encoding        字符集
     * @param nativeSignals   是否注册原生信号处理器
     * @param signalHandler   信号处理器；为 null 时使用 {@link Terminal.SignalHandler#SIG_DFL}
     * @param paused          是否以暂停状态创建
     * @return 终端实例
     * @throws IOException 控制台初始化失败时
     */
    public static Terminal create(TerminalProvider provider, SystemStream systemStream, String name, String type,
            boolean ansiPassThrough, Charset encoding, boolean nativeSignals,
            Terminal.SignalHandler signalHandler, boolean paused) throws IOException {
        IntByReference inMode = new IntByReference();
        Kernel32.INSTANCE.GetConsoleMode(CONSOLE_IN, inMode);
        Pointer console;
        switch (systemStream) {
            case Output:
                console = CONSOLE_OUT;
                break;
            case Error:
                console = CONSOLE_ERR;
                break;
            default:
                throw new IllegalArgumentException("Unsupported stream for console: " + systemStream);
        }
        IntByReference outMode = new IntByReference();
        Kernel32.INSTANCE.GetConsoleMode(console, outMode);

        Writer writer;
        if (ansiPassThrough) {
            type = type != null ? type : (OSUtils.IS_CONEMU ? TYPE_WINDOWS_CONEMU : TYPE_WINDOWS);
            writer = new JnaWinConsoleWriter(console);
        } else if (enableVtp(console, outMode.getValue())) {
            type = type != null ? type : TYPE_WINDOWS_VTP;
            writer = new JnaWinConsoleWriter(console);
        } else if (OSUtils.IS_CONEMU) {
            type = type != null ? type : TYPE_WINDOWS_CONEMU;
            writer = new JnaWinConsoleWriter(console);
        } else {
            type = type != null ? type : TYPE_WINDOWS;
            writer = new WindowsAnsiWriter(new BufferedWriter(new JnaWinConsoleWriter(console)), console);
        }

        Terminal.SignalHandler handler = signalHandler != null ? signalHandler : Terminal.SignalHandler.SIG_DFL;
        Terminal terminal = new ClaraWinSysTerminal(provider, systemStream, writer, name, type, encoding,
                nativeSignals, handler, CONSOLE_IN, inMode.getValue(), console, outMode.getValue());
        if (!paused) {
            terminal.resume();
        }
        return terminal;
    }

    /**
     * 把 Shift+Enter / Ctrl+Enter 改写为行编辑器可识别的序列；其余按键保持 JLine 默认映射。
     *
     * @param virtualKey 虚拟键码
     * @param modifier   修饰键位（Ctrl=4、Alt=2、Shift=1）
     * @return 转义序列；无需改写时为 null
     */
    @Override
    protected String getEscapeSequence(short virtualKey, int modifier) {
        String sequence = ConsoleKeySequences.windowsEscapeSequence(virtualKey, modifier);
        return sequence != null ? sequence : super.getEscapeSequence(virtualKey, modifier);
    }

    /**
     * 尝试开启虚拟终端处理，使 ANSI 序列可直通控制台。
     *
     * @param console 控制台句柄
     * @param outMode 当前输出模式
     * @return 开启成功时返回 true
     */
    private static boolean enableVtp(Pointer console, int outMode) {
        try {
            Kernel32.INSTANCE.SetConsoleMode(console, outMode | ENABLE_VIRTUAL_TERMINAL_PROCESSING);
            return true;
        } catch (LastErrorException unsupported) {
            return false;
        }
    }

}
