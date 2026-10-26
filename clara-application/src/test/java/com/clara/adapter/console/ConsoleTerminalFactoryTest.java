package com.clara.adapter.console;

import org.jline.terminal.TerminalBuilder;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

/**
 * {@link ConsoleTerminalFactory} 的终端参数解析：编码属性优先、非法值回退 UTF-8；终端类型属性优先。
 */
public class ConsoleTerminalFactoryTest {

    private final String originalEncoding = System.getProperty(TerminalBuilder.PROP_ENCODING);
    private final String originalType = System.getProperty(TerminalBuilder.PROP_TYPE);

    @After
    public void tearDown() {
        restore(TerminalBuilder.PROP_ENCODING, originalEncoding);
        restore(TerminalBuilder.PROP_TYPE, originalType);
    }

    @Test
    public void resolvesEncodingWithFallback() throws Exception {
        Method method = ConsoleTerminalFactory.class.getDeclaredMethod("resolveEncoding");
        method.setAccessible(true);

        System.clearProperty(TerminalBuilder.PROP_ENCODING);
        assertEquals(StandardCharsets.UTF_8, method.invoke(null));

        System.setProperty(TerminalBuilder.PROP_ENCODING, "ISO-8859-1");
        assertEquals(Charset.forName("ISO-8859-1"), method.invoke(null));

        System.setProperty(TerminalBuilder.PROP_ENCODING, "no-such-charset");
        assertEquals(StandardCharsets.UTF_8, method.invoke(null));
    }

    @Test
    public void prefersConfiguredTerminalType() throws Exception {
        Method method = ConsoleTerminalFactory.class.getDeclaredMethod("resolveTerminalType");
        method.setAccessible(true);

        System.setProperty(TerminalBuilder.PROP_TYPE, "xterm-256color");
        assertEquals("xterm-256color", method.invoke(null));

        System.clearProperty(TerminalBuilder.PROP_TYPE);
        assertEquals(System.getenv("TERM"), method.invoke(null));
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}