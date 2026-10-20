package com.clara.adapter.console;

import com.clara.context.CancellationToken;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * ESC 取消监听：终端可用性降级、监听会话的启动/停止与确认状态机驱动的按键处理。
 */
public class CancelKeyConsoleTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    /**
     * 终端不可用时创建监听器应降级为空操作，关闭与空令牌监听都不应失败。
     */
    @Test
    public void monitorDegradesWhenTerminalUnavailable() throws Exception {
        ConsoleView view = new ConsoleView(folder.newFolder("logs-monitor").toPath(), false, "never");
        try (CancelKeyMonitor monitor = CancelKeyMonitor.create(view)) {
            assertNotNull(monitor);
            CancelKeySession session = monitor.watch(null);
            assertNotNull(session);
            session.close();
            if (!monitor.isAvailable()) {
                monitor.watch(new CancellationToken()).close();
            }
        } finally {
            view.close();
        }
    }

    /**
     * 连续两次 ESC 确认后应置位取消令牌。
     */
    @Test
    public void cancelsAfterDoubleEscape() throws Exception {
        CancellationToken token = new CancellationToken();
        assertTrue(runSession(new byte[]{27, 27}, token));
    }

    /**
     * 确认窗口内按其它键应忽略，随后可再次 ESC + 回车确认。
     */
    @Test
    public void ignoresOtherKeyThenCancelsWithEnter() throws Exception {
        CancellationToken token = new CancellationToken();
        assertTrue(runSession(new byte[]{27, 'x', 27, 13}, token));
    }

    /**
     * 读取异常时监听应静默退出，且不置位取消令牌。
     */
    @Test
    public void readFailureStopsQuietly() throws Exception {
        CancellationToken token = new CancellationToken();
        assertFalse(runSession(new FailingInputStream(), token));
    }

    /**
     * 未启动即关闭、以及空令牌启动都应为安全操作。
     */
    @Test
    public void handlesLifecycleEdges() throws Exception {
        ConsoleView view = new ConsoleView(folder.newFolder("logs-edges").toPath(), false, "never");
        DumbTerminal terminal = new DumbTerminal(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
        try {
            CancelKeySession idle = new CancelKeySession(view, terminal, null);
            idle.close();
            idle.start();
            idle.close();
        } finally {
            terminal.close();
            view.close();
        }
    }

    private boolean runSession(byte[] keys, CancellationToken token) throws Exception {
        return runSession(new ByteArrayInputStream(keys), token);
    }

    private boolean runSession(InputStream input, CancellationToken token) throws Exception {
        ConsoleView view = new ConsoleView(folder.newFolder().toPath(), false, "never");
        DumbTerminal terminal = new DumbTerminal(input, new ByteArrayOutputStream());
        CancelKeySession session = new CancelKeySession(view, terminal, token);
        try {
            session.start();
            for (int attempt = 0; attempt < 60 && !token.isCancellationRequested(); attempt++) {
                Thread.sleep(50L);
            }
            return token.isCancellationRequested();
        } finally {
            session.close();
            terminal.close();
            view.close();
        }
    }

    /**
     * 读取即失败的输入流，用于覆盖终端的异常读取路径。
     */
    private static final class FailingInputStream extends InputStream {

        @Override
        public int read() throws IOException {
            throw new IOException("terminal closed");
        }
    }
}