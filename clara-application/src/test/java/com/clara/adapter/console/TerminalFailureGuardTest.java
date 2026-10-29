package com.clara.adapter.console;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 终端异常判定：只有输入泵线程与链接错误才视为不可恢复，其他线程异常不能结束进程。
 */
public class TerminalFailureGuardTest {

    /**
     * 输入泵线程按名称识别：Windows 的 WindowsStreamPump 与其他平台的 *PumpThread / TerminalPump。
     */
    @Test
    public void recognizesPumpThreadByName() {
        assertTrue(TerminalFailureGuard.isPumpThread(new Thread("WindowsStreamPump")));
        assertTrue(TerminalFailureGuard.isPumpThread(new Thread("TerminalPump")));
        assertTrue(TerminalFailureGuard.isPumpThread(new Thread("ClaraPumpThread")));
        assertFalse(TerminalFailureGuard.isPumpThread(new Thread("main")));
        assertFalse(TerminalFailureGuard.isPumpThread(new Thread("telemetry-exporter")));
        assertFalse(TerminalFailureGuard.isPumpThread(null));
    }

    /**
     * 链接错误在任何线程上都意味着类加载体系已损坏，必须结束进程。
     */
    @Test
    public void linkageErrorIsFatalOnAnyThread() {
        assertTrue(TerminalFailureGuard.isFatal(new Thread("main"), new NoClassDefFoundError("com/clara/X")));
        assertTrue(TerminalFailureGuard.isFatal(null, new NoClassDefFoundError("com/clara/X")));
    }

    /**
     * 普通运行时异常只打印，不结束进程，避免误伤后台任务。
     */
    @Test
    public void runtimeFailureOnWorkerThreadIsNotFatal() {
        assertFalse(TerminalFailureGuard.isFatal(new Thread("worker"), new IllegalStateException("boom")));
        assertFalse(TerminalFailureGuard.isFatal(null, new IllegalStateException("boom")));
    }

    /**
     * 输入泵线程上任何异常都会让控制台失去输入能力，一律按不可恢复处理。
     */
    @Test
    public void pumpFailureIsFatalRegardlessOfType() {
        assertTrue(TerminalFailureGuard.isFatal(new Thread("WindowsStreamPump"), new IllegalStateException("boom")));
        assertTrue(TerminalFailureGuard.isFatal(new Thread("WindowsStreamPump"), new Error("boom")));
    }
}
