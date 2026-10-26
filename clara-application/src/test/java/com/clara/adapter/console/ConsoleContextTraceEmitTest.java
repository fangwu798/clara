package com.clara.adapter.console;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertTrue;

/**
 * {@link ConsoleContextTrace} 的实例输出路径：两种 onTrace 重载都落到控制台日志。
 */
public class ConsoleContextTraceEmitTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private PrintStream originalOut;
    private ConsoleView view;
    private Path logs;

    @Before
    public void setUp() throws Exception {
        originalOut = System.out;
        logs = folder.newFolder("logs-trace").toPath();
        view = new ConsoleView(logs, false, "never");
    }

    @After
    public void tearDown() {
        if (view != null) {
            view.close();
        }
        System.setOut(originalOut);
    }

    @Test
    public void writesHitAndPhaseLinesToLog() throws Exception {
        ConsoleContextTrace trace = new ConsoleContextTrace(view);

        trace.onTrace("code_graph", 2, 15, Arrays.asList("pattern=X", "  ", null));
        trace.onTrace("intent", "复杂任务", true, 20, null);
        trace.onTrace("validation", "格式不合法", false, 5, Collections.singletonList("missing steps"));

        String log = readLog();
        assertTrue(log.contains("[图谱]"));
        assertTrue(log.contains("命中 2 条"));
        assertTrue(log.contains("pattern=X"));
        assertTrue(log.contains("[意图]"));
        assertTrue(log.contains("复杂任务"));
        assertTrue(log.contains("[校验]"));
        assertTrue(log.contains("格式不合法"));
        assertTrue(log.contains("missing steps"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNullView() {
        new ConsoleContextTrace(null);
    }

    private String readLog() throws Exception {
        File[] files = logs.toFile().listFiles();
        assertTrue(files != null && files.length == 1);
        return new String(Files.readAllBytes(files[0].toPath()), StandardCharsets.UTF_8);
    }
}