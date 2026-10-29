package com.clara.adapter.console;

import org.jline.terminal.Terminal;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 终端异常兜底：终端输入泵线程一旦退出，控制台就再也收不到按键。
 *
 * <p>为什么需要：Windows 终端的输入由 JLine 的 {@code WindowsStreamPump} 线程持续读取并投递，
 * 该线程抛出未捕获异常后进程仍在运行，但 {@code readLine()} 会永久阻塞——用户看到的现象就是
 * 「程序还在、敲什么都没反应、也退不出去」。这里把这类必死状态转成一次明确的报错并结束进程。
 *
 * <p>触发范围（只处理不可恢复的情况，避免误伤后台线程）：
 * <ul>
 *   <li>输入泵线程（{@code *StreamPump} / {@code *PumpThread} / {@code TerminalPump}）抛出任何异常；</li>
 *   <li>任意线程抛出 {@link LinkageError}：典型是运行期间 {@code clara.jar} 被重新打包或损坏，
 *       后续类加载全部失败，进程已无法继续工作。</li>
 * </ul>
 * 其他线程的普通异常仍按 JVM 默认方式打印，不结束进程。
 *
 * <p>线程安全：安装标记与退出标记均为原子量，重复安装或并发触发只执行一次退出流程。
 */
public final class TerminalFailureGuard {

    /**
     * 是否已经安装全局处理器：重复安装会覆盖别人的处理器，只做一次。
     */
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    /**
     * 是否已经进入退出流程：多个线程同时失败时只清理、退出一次。
     */
    private static final AtomicBoolean SHUTTING_DOWN = new AtomicBoolean(false);

    /**
     * 退出兜底等待毫秒数：关闭终端超过该时间仍未结束进程，直接强制结束。
     */
    private static final long FORCED_EXIT_MILLIS = 3000L;

    /**
     * 当前系统终端，用于退出前恢复控制台模式；尚未打开或已关闭时为 null。
     */
    private static volatile Terminal activeTerminal;

    private TerminalFailureGuard() {
    }

    /**
     * 安装全局未捕获异常处理器：应在进程入口、创建终端之前调用一次。
     */
    public static void install() {
        if (INSTALLED.compareAndSet(false, true)) {
            Thread.setDefaultUncaughtExceptionHandler(TerminalFailureGuard::handle);
        }
    }

    /**
     * 登记当前终端，供异常退出时恢复控制台模式。
     *
     * @param terminal 已打开的系统终端，可为 null
     */
    public static void bind(Terminal terminal) {
        activeTerminal = terminal;
    }

    /**
     * 判断线程是否为终端输入泵线程。
     *
     * @param thread 线程，可为 null
     * @return 是输入泵线程时返回 true
     */
    static boolean isPumpThread(Thread thread) {
        if (thread == null) {
            return false;
        }
        String name = thread.getName();
        if (name == null) {
            return false;
        }
        return name.contains("StreamPump") || name.contains("PumpThread") || name.contains("TerminalPump");
    }

    /**
     * 判断异常是否已让进程进入不可恢复状态。
     *
     * @param thread  抛出异常的线程，可为 null
     * @param failure 未捕获异常，非 null
     * @return 需要结束进程时返回 true
     */
    static boolean isFatal(Thread thread, Throwable failure) {
        return failure instanceof LinkageError || isPumpThread(thread);
    }

    /**
     * 全局未捕获异常入口：不可恢复时打印原因并退出，其余情况按 JVM 默认格式打印。
     *
     * @param thread  抛出异常的线程，可为 null
     * @param failure 未捕获异常，非 null
     */
    private static void handle(Thread thread, Throwable failure) {
        if (!isFatal(thread, failure)) {
            printUncaught(thread, failure);
            return;
        }
        if (!SHUTTING_DOWN.compareAndSet(false, true)) {
            return;
        }
        printFatal(thread, failure);
        shutdown();
    }

    /**
     * 与 JVM 默认行为一致的未捕获异常打印。
     *
     * @param thread  线程，可为 null
     * @param failure 异常，非 null
     */
    private static void printUncaught(Thread thread, Throwable failure) {
        String name = thread == null ? "unknown" : thread.getName();
        System.err.print("Exception in thread \"" + name + "\" ");
        failure.printStackTrace(System.err);
        System.err.flush();
    }

    /**
     * 打印不可恢复错误的处理建议。
     *
     * @param thread  线程，可为 null
     * @param failure 异常，非 null
     */
    private static void printFatal(Thread thread, Throwable failure) {
        String name = thread == null ? "unknown" : thread.getName();
        System.err.println();
        System.err.println(isPumpThread(thread)
                ? "[error] 控制台输入线程已退出：" + name
                : "[error] 线程 " + name + " 抛出不可恢复的错误");
        System.err.println("[error] 原因：" + describe(failure));
        System.err.println("[error] 常见原因：运行期间 clara.jar 被重新打包 / 替换，或 jar 文件损坏。");
        System.err.println("[error] 处理：重启 clara；若仍失败，重新打包：mvn -pl clara-application -am package -DskipTests");
        System.err.println("[error] 控制台无法再接收输入，进程即将退出，避免卡死。");
        System.err.flush();
    }

    /**
     * 拼接异常类型与消息。
     *
     * @param failure 异常，非 null
     * @return 形如 {@code java.lang.NoClassDefFoundError: com/x/Y} 的文本，非 null
     */
    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        String type = failure.getClass().getName();
        return message == null || message.trim().isEmpty() ? type : type + ": " + message;
    }

    /**
     * 启动清理线程恢复控制台并退出，同时挂一个超时看门狗防止关闭流程被卡住。
     */
    private static void shutdown() {
        Thread cleanup = new Thread(TerminalFailureGuard::closeTerminalAndExit, "clara-terminal-failure-cleanup");
        cleanup.setDaemon(false);
        cleanup.start();
        Thread watchdog = new Thread(() -> {
            sleepQuietly(FORCED_EXIT_MILLIS);
            Runtime.getRuntime().halt(1);
        }, "clara-terminal-failure-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /**
     * 关闭终端（恢复控制台模式）后结束进程。
     */
    private static void closeTerminalAndExit() {
        Terminal terminal = activeTerminal;
        activeTerminal = null;
        if (terminal != null) {
            try {
                terminal.close();
            } catch (Exception ignored) {
                // 终端已失效时关闭失败是预期结果，不影响退出
            }
        }
        System.exit(1);
    }

    /**
     * 休眠指定毫秒，被中断时恢复中断标记。
     *
     * @param millis 毫秒数
     */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
