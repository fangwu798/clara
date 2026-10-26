package com.clara.adapter.console;

import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;

/**
 * {@link ConsoleInput} 的历史输入：去空白、去重、超长截断，以及批量恢复只执行一次。
 */
public class ConsoleInputHistoryTest {

    @Test
    public void trimsDeduplicatesAndTruncatesHistory() throws Exception {
        Terminal terminal = new DumbTerminal(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
        ConsoleInput input = new ConsoleInput(terminal, new StringReader(""));
        try {
            input.addHistory("  hello  ");
            input.addHistory("hello");
            input.addHistory("   ");
            input.addHistory(null);
            StringBuilder longLine = new StringBuilder();
            for (int index = 0; index < 5000; index++) {
                longLine.append('x');
            }
            input.addHistory(longLine.toString());

            History history = historyOf(input);
            assertEquals(2, history.size());
            assertEquals("hello", history.get(0));
            assertEquals(4000, history.get(1).length());
        } finally {
            input.close();
            terminal.close();
        }
    }

    @Test
    public void seedsHistoryOnlyOnce() throws Exception {
        Terminal terminal = new DumbTerminal(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
        ConsoleInput input = new ConsoleInput(terminal, new StringReader(""));
        try {
            input.seedHistory(Arrays.asList("a", "b", "c"));
            input.seedHistory(Arrays.asList("d"));

            History history = historyOf(input);
            assertEquals(3, history.size());
            assertEquals("a", history.get(0));
            assertEquals("c", history.get(2));
        } finally {
            input.close();
            terminal.close();
        }
    }

    @Test
    public void nullSeedMarksHistoryAsSeeded() throws Exception {
        Terminal terminal = new DumbTerminal(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
        ConsoleInput input = new ConsoleInput(terminal, new StringReader(""));
        try {
            input.seedHistory(null);
            input.seedHistory(Arrays.asList("late"));

            assertEquals(0, historyOf(input).size());
        } finally {
            input.close();
            terminal.close();
        }
    }

    private static History historyOf(ConsoleInput input) throws Exception {
        Field field = ConsoleInput.class.getDeclaredField("lineReader");
        field.setAccessible(true);
        LineReader reader = (LineReader) field.get(input);
        return reader.getHistory();
    }
}