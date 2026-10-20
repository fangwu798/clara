package com.clara.codegraph;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 图谱控制台的按需挂载结果渲染：纯文本 / JSON 的渲染分支与平台命令探测，不启动任何子进程。
 */
public class CodeGraphConsoleMountRenderingTest {

    /**
     * 已可见且只有一条说明时直接返回该说明。
     */
    @Test
    public void returnsSingleVisibleNote() throws Exception {
        assertEquals("已可见：e: -> /work/repo",
                describeMount(result(0, "[cbm-mount] 已可见：e: -> /work/repo\n")));
    }

    /**
     * 含跳过说明时输出完整报告。
     */
    @Test
    public void rendersSkippedReport() throws Exception {
        String report = describeMount(result(0, "[cbm-mount] 跳过：未运行容器\n[cbm-mount] 已可见\n"));
        assertTrue(report.contains("跳过按需挂载"));
        assertTrue(report.contains("说明：跳过：未运行容器；已可见"));
    }

    /**
     * 建索引中（退出码 3）走单独文案。
     */
    @Test
    public void rendersIndexingReport() throws Exception {
        String report = describeMount(result(3, "[cbm-mount] 正在建索引\n"));
        assertTrue(report.contains("正在建索引"));
        assertFalse(report.contains("原因："));
    }

    /**
     * 失败时优先取最后一行非前缀输出作为原因。
     */
    @Test
    public void rendersFailureWithPlainReason() throws Exception {
        String report = describeMount(result(7, "[cbm-mount] 挂载失败\nDocker Desktop 未启动\n"));
        assertTrue(report.contains("退出码 7"));
        assertTrue(report.contains("原因：Docker Desktop 未启动"));
    }

    /**
     * 没有普通输出行时回退到最后一条说明。
     */
    @Test
    public void rendersFailureFallingBackToLastNote() throws Exception {
        String report = describeMount(result(9, "[cbm-mount] 只有说明\n"));
        assertTrue(report.contains("原因：只有说明"));
    }

    /**
     * 退出码为负表示输出已是可打印说明，原样返回。
     */
    @Test
    public void returnsRawOutputForNegativeExit() throws Exception {
        assertEquals("[error] 启动挂载脚本失败", describeMount(result(-1, "[error] 启动挂载脚本失败")));
    }

    /**
     * 说明提取、跳过判定与原因选择等辅助逻辑。
     */
    @Test
    public void parsesMountNotesAndReason() throws Exception {
        List<String> notes = mountNotes("noise\n[cbm-mount] a\n  [cbm-mount] b  \n");
        assertEquals(Arrays.asList("a", "b"), notes);
        assertTrue(anySkipped(Arrays.asList("已可见", "跳过：x")));
        assertFalse(anySkipped(Arrays.asList("已可见", "已补挂")));
        assertEquals("last", mountReason("[cbm-mount] note\nfirst\nlast\n", Arrays.asList("note")));
        assertEquals("note", mountReason("[cbm-mount] note\n", Arrays.asList("note")));
        assertEquals("", mountReason("", Arrays.asList()));
    }

    /**
     * 平台命令探测与流读取。
     */
    @Test
    public void exposesPlatformCommandsAndReadsStream() throws Exception {
        assertTrue(((String) invoke("pythonCommand")).length() > 0);
        assertTrue(((String) invoke("powershellCommand")).length() > 0);
        String osName = System.getProperty("os.name");
        assertEquals(osName != null && osName.toLowerCase().startsWith("windows"), invoke("isWindows"));
        byte[] payload = "依赖".getBytes(StandardCharsets.UTF_8);
        Object text = invoke("readAll", new Class<?>[]{java.io.InputStream.class}, new ByteArrayInputStream(payload));
        assertEquals("依赖", text);
    }

    private static Object result(int exitCode, String output) throws Exception {
        Class<?> type = Class.forName("com.clara.codegraph.CodeGraphConsole$Result");
        Constructor<?> constructor = type.getDeclaredConstructor(int.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(exitCode, output);
    }

    private static String describeMount(Object result) throws Exception {
        Class<?> type = Class.forName("com.clara.codegraph.CodeGraphConsole$Result");
        return (String) invoke("describeMount", new Class<?>[]{type}, result);
    }

    @SuppressWarnings("unchecked")
    private static List<String> mountNotes(String output) throws Exception {
        return (List<String>) invoke("mountNotes", new Class<?>[]{String.class}, output);
    }

    @SuppressWarnings("unchecked")
    private static boolean anySkipped(List<String> notes) throws Exception {
        return (Boolean) invoke("anySkipped", new Class<?>[]{List.class}, notes);
    }

    private static String mountReason(String output, List<String> notes) throws Exception {
        return (String) invoke("mountReason", new Class<?>[]{String.class, List.class}, output, notes);
    }

    private static Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = CodeGraphConsole.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException failure) {
            throw (Exception) failure.getCause();
        }
    }

    private static Object invoke(String name) throws Exception {
        return invoke(name, new Class<?>[0]);
    }
}