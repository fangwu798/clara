package com.clara.adapter.console;

import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 控制台输入降级路径：终端不可用时仍可按行读取，历史调用为空操作。
 */
public class ConsoleInputTest {

    /**
     * 无终端时退化为父类读取，历史相关方法不抛异常。
     */
    @Test
    public void fallsBackToPlainReading() throws Exception {
        ConsoleInput input = new ConsoleInput(null, new StringReader("hello\nworld\n"));
        assertFalse(input.isLineEditingAvailable());
        assertFalse(input.hasBottomStatus());
        assertEquals("hello", input.readLine());
        assertEquals("world", input.readLine());
        assertNull(input.readLine());
        input.addHistory("ignored");
        input.addHistory(null);
        input.seedHistory(Collections.singletonList("ignored"));
        input.seedHistory(null);
        input.close();
    }

    /**
     * 终端可用时应成功构建行编辑器，Ctrl+Enter 的换行绑定不产生冲突。
     */
    @Test
    public void buildsLineEditorAndBindsCtrlEnter() throws Exception {
        Terminal terminal = new DumbTerminal(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
        ConsoleInput input = new ConsoleInput(terminal, new StringReader(""));
        assertTrue(input.isLineEditingAvailable());
        input.close();
        terminal.close();
    }

    /**
     * Ctrl+Enter 序列在缓冲区插入换行，而不是提交；末尾的 Enter 才结束本次输入。
     */
    @Test
    public void insertsNewlineForCtrlEnterSequence() throws Exception {
        byte[] keys = ("abc" + ConsoleKeySequences.CTRL_ENTER + "def\r")
                .getBytes(StandardCharsets.UTF_8);
        Terminal terminal = new DumbTerminal(new ByteArrayInputStream(keys), new ByteArrayOutputStream());
        ConsoleInput input = new ConsoleInput(terminal, new StringReader(""));
        assertEquals("abc\ndef", input.readLine());
        input.close();
        terminal.close();
    }

    /**
     * Shift+Enter 同样只在缓冲区插入换行，末尾的 Enter 才提交。
     */
    @Test
    public void insertsNewlineForShiftEnterSequence() throws Exception {
        byte[] keys = ("abc" + ConsoleKeySequences.SHIFT_ENTER + "def\r")
                .getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Terminal terminal = new DumbTerminal(new ByteArrayInputStream(keys), output);
        ConsoleInput input = new ConsoleInput(terminal, new StringReader(""));
        assertEquals("abc\ndef", input.readLine());
        assertFalse(new String(output.toByteArray(), StandardCharsets.UTF_8).contains("..."));
        input.close();
        terminal.close();
    }

    /**
     * 输入行展示：› 提示符、空缓冲区占位提示与下方状态行。
     */
    @Test
    public void rendersPromptPlaceholderAndStatusLine() throws Exception {
        ByteArrayInputStream keys = new ByteArrayInputStream("\r".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Terminal terminal = new DumbTerminal(keys, output);
        ConsoleInput input = new ConsoleInput(terminal, new StringReader(""));
        input.setStatusLine(() -> "deepseek-response · deepseek-v4-pro · E:/work");
        assertEquals("", input.readLine());
        String rendered = new String(output.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(rendered.contains("›"));
        assertTrue(rendered.contains("输入任务"));
        assertTrue(rendered.contains("deepseek-response"));
        input.setStatusLine(null);
        input.close();
        terminal.close();
    }

    /**
     * 终端支持滚动区域时，状态行固定在最后一行（预留 1..rows-1 的滚动区域），
     * 因此模型思考期间持续输出也不会把它冲掉。
     */
    @Test
    public void pinsStatusLineToBottomWhenScrollRegionSupported() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Terminal terminal = TerminalBuilder.builder().system(false).type("xterm")
                .streams(new ByteArrayInputStream(new byte[0]), output).build();
        terminal.setSize(new Size(120, 30));
        ConsoleInput input = new ConsoleInput(terminal, new StringReader(""));
        assertTrue(input.hasBottomStatus());
        input.setStatusLine(() -> "deepseek-response · deepseek-v4-pro · E:/work");
        String rendered = new String(output.toByteArray(), StandardCharsets.UTF_8);
        assertTrue("应把最后一行留给状态栏", rendered.contains("\033[1;29r"));
        assertTrue(rendered.contains("deepseek-response"));
        input.close();
        terminal.close();
    }

    /**
     * 状态行每次刷新都要整行重画：JLine 只做差异重绘，开头字符被并发输出冲掉后不会自动补回。
     */
    @Test
    public void redrawsWholeStatusLineEvenWhenContentUnchanged() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Terminal terminal = TerminalBuilder.builder().system(false).type("xterm")
                .streams(new ByteArrayInputStream(new byte[0]), output).build();
        terminal.setSize(new Size(120, 30));
        ConsoleInput input = new ConsoleInput(terminal, new StringReader(""));
        try {
            assertTrue(input.hasBottomStatus());
            input.setStatusLine(() -> "deepseek-response · deepseek-v4-pro · E:/work");
            output.reset();
            input.setStatusLine(() -> "deepseek-response · deepseek-v4-pro · E:/work");
            String redrawn = new String(output.toByteArray(), StandardCharsets.UTF_8);
            assertTrue("内容未变时也要整行重画", redrawn.contains("  deepseek-response"));
            assertTrue("重画必须绝对定位到最后一行的第 1 列", redrawn.contains("\033[30;1H"));
            assertTrue("重画前要擦掉整行，避免残留", redrawn.contains("\033[K"));
        } finally {
            input.close();
            terminal.close();
        }
    }
}
