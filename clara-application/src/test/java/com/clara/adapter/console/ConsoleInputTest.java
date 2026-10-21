package com.clara.adapter.console;

import org.jline.terminal.Terminal;
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
}
